/*
 * Copyright (C) 2026 John Garner <segfaultcoredump@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pikatimer.pikareader.readers.impinj;

import com.google.auto.service.AutoService;
import com.impinj.octane.AntennaConfigGroup;
import com.impinj.octane.FeatureSet;
import com.impinj.octane.ImpinjReader;
import com.impinj.octane.OctaneSdkException;
import com.impinj.octane.ReportConfig;
import com.impinj.octane.ReportMode;
import com.impinj.octane.RshellEngine;
import com.impinj.octane.RshellReply;
import com.impinj.octane.SearchMode;
import com.impinj.octane.Settings;
import com.jcraft.jsch.JSch;
import com.pikatimer.pikareader.readers.LlrpTls;
import com.pikatimer.pikareader.readers.RFIDReader;
import com.pikatimer.pikareader.readers.ReaderClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
@AutoService(RFIDReader.class)
public class Impinj implements RFIDReader {

    private static final Logger logger = LoggerFactory.getLogger(Impinj.class);

    static {
        enableLegacySshAlgorithms();
    }

    private final ImpinjReader reader;
    private ImpinjPowerLevels powerLevel = ImpinjPowerLevels.HIGH; // 30.0dBm / 1.0W. A go-to value for most stuff. 

    private Map<Integer, String> antennaStatus = new HashMap<>();

    private JSONObject readerConfig = new JSONObject();

    private String readerIP;
    private int readerPort = LlrpTls.PORT;
    private boolean useTls = false;
    private Integer readerID;
    private Boolean reading = false;
    private Boolean connected = false;
    
    private Semaphore reader_command_semaphore = new Semaphore(1);
    private volatile boolean connectHintLogged = false;

    private Integer antennaCount = 0;
    private long lastAntennaUpdateTS = 0L;

    // Inventory settings from the config. A null reader mode means "pick the
    // default for this reader" (see connect()). Modes are Impinj RF mode
    // numbers; ImpinjRfMode has the names.
    private Integer configuredReaderMode = null;
    private SearchMode searchMode = SearchMode.DualTargetBtoASelect;
    private int session = 2; // the Octane SDK default
    private int tagPopulation = 128;

    // "Clock": "Local" (default) sets the reader's clock from the local clock over SSH.
    // "Clock": "NTP" leaves the reader's own NTP-synced clock alone.
    private boolean useNTP = false;
    // Either way the reader's timestamps are used as-is; this just warns if they're off.
    private ReaderClock clock = new ReaderClock(-1, ReaderClock.Mode.READER, null);

    public Impinj() {
        readerID = -1;
        readerIP = "";

        try {
            Class.forName("com.impinj.octane.ImpinjReader");
        } catch (ClassNotFoundException ex) {
            logger.error("Impinj Octane Libraries not found. Please make sure that the octane jar is in the classpath");
            System.exit(1);
        }
        reader = new ImpinjReader();
    }

    @Override
    public Boolean isReading() {
        return reading;
    }
    
    @Override
    public String getStatus(){
        return reading?"Reading":reader.isConnected()?"Connected":"Disconnected";
    }

    @Override
    public String getType() {
        return "IMPINJ";
    }

    @Override
    public void setClock() {
        logger.trace("Entering Impinj::setClock() for {}", readerIP);

        if (useNTP) {
            // "Clock": "NTP" -- the reader keeps its own NTP-synced time and
            // we leave its clock (and its NTP settings) alone
            logger.info("Reader {} ({}) uses NTP for its clock, so PikaReader will not change it: {}", readerID, readerIP, clock.describe());
            return;
        }

        try {

            logger.info("Setting the clock for {}...", readerIP);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd'-'HH:mm:ss");

            RshellEngine rshell = new RshellEngine();

            /* login using ssh*/
            rshell.openSecureSession(readerIP, "root", "impinj", 10000);

            // make sure NTP is disabled
            boolean ok = sendRShell(rshell, "config network ntp disable");

            // Issue the command as close to the turn of the second as possible
            // We fudge the time by a 10th of a second to account for the latency of the set command.
            int gap = 0;
            while (gap < Instant.now().plusNanos(100000000).getNano()) {
                gap = Instant.now().plusNanos(100000000).getNano();
                Thread.sleep(10);
            }

            logger.debug("{} setClock() Start Timestamp: {}", readerIP, Instant.now());

            ok &= sendRShell(rshell, "config system time " + OffsetDateTime.now(ZoneOffset.UTC).plusNanos(100000000).format(formatter));

            logger.debug("{} setClock() Finish Timestamp: {}", readerIP, Instant.now());

            rshell.close();
            if (ok) {
                logger.info("Clock set for {}", readerIP);
            } else {
                logger.warn("The reader at {} did not accept every clock command. Its clock may be wrong; watch for clock skew warnings.", readerIP);
            }

            // Older samples were taken against the old clock
            clock.reset();

        } catch (Exception ex) {
            logger.error("Unable to set the clock for reader {} ({}) over SSH: {}", readerID, readerIP, rootMessage(ex));
            logger.debug("setClock exception", ex);
        }

        logger.trace("Exiting Impinj::setClock()");

    }

    /**
     * Send one RShell command and check the Status in the reply
     * (e.g. "0,Success" or "3,Invalid-Command").
     *
     * @return true if the reader reported success
     */
    private boolean sendRShell(RshellEngine rshell, String cmd) throws OctaneSdkException {
        logger.debug("Sending command '{}' to {}", cmd, readerIP);
        String reply = rshell.send(cmd);
        logger.trace("Raw Reply: {}", reply);

        String status = reply == null ? null : new RshellReply(reply).get("Status");
        if (status != null && status.startsWith("0,")) {
            return true;
        }
        logger.warn("Reader {} ({}) did not accept '{}': Status {}", readerID, readerIP, cmd, status);
        return false;
    }

    /**
     * Log the reader's NTP status (read-only) so it is easy to confirm NTP is
     * working when "Clock" is "NTP".
     */
    private void logNtpStatus() {
        try {
            RshellEngine rshell = new RshellEngine();
            rshell.openSecureSession(readerIP, "root", "impinj", 10000);
            String reply = rshell.send("show network ntp");
            rshell.close();
            String status = reply == null ? null : new RshellReply(reply).get("Status");
            if (status != null && !status.startsWith("0,")) {
                logger.warn("Reader {} ({}) did not accept 'show network ntp': Status {}", readerID, readerIP, status);
            }
            logger.info("Reader {} ({}) NTP status:\n{}", readerID, readerIP, reply);
        } catch (Exception ex) {
            logger.warn("Reader {} ({}): unable to read the NTP status over SSH ({}). The reader's clock will still be used as-is.",
                    readerID, readerIP, rootMessage(ex));
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage() != null ? root.getMessage() : root.toString();
        return root == ex ? m : ex.getMessage() + " (" + m + ")";
    }

    /*
     * Octane 6 replaced JSch 0.1.54 with the mwiede JSch fork, whose defaults
     * no longer offer ssh-rsa host keys, SHA-1 key exchange or CBC ciphers.
     * Older Speedway (R220/R420) firmware may only offer those, which would
     * stop "Clock": "Local" from logging in. RshellEngine uses JSch's global
     * defaults, so add back everything JSch 0.1.54 offered that the fork still
     * implements. They go at the end of each list, so readers with newer SSH
     * servers (e.g. the R700) still negotiate the modern algorithms.
     */
    private static void enableLegacySshAlgorithms() {
        try {
            appendJSchConfig("server_host_key", "ssh-rsa", "ssh-dss");
            appendJSchConfig("kex", "diffie-hellman-group14-sha1", "diffie-hellman-group-exchange-sha1", "diffie-hellman-group1-sha1");
            String[] ciphers = {"aes128-cbc", "aes192-cbc", "aes256-cbc", "3des-ctr", "3des-cbc", "blowfish-cbc"};
            appendJSchConfig("cipher.c2s", ciphers);
            appendJSchConfig("cipher.s2c", ciphers);
            String[] macs = {"hmac-md5", "hmac-sha1-96", "hmac-md5-96"};
            appendJSchConfig("mac.c2s", macs);
            appendJSchConfig("mac.s2c", macs);
            for (String key : new String[]{"server_host_key", "kex", "cipher.c2s", "mac.c2s"}) {
                logger.debug("JSch {}: {}", key, JSch.getConfig(key));
            }
        } catch (Throwable t) {
            // Never stop the reader from loading over this
            logger.warn("Unable to enable the legacy SSH algorithms for older Impinj readers: {}", t.toString());
        }
    }

    private static void appendJSchConfig(String key, String... algorithms) {
        String current = JSch.getConfig(key);
        List<String> list = new ArrayList<>();
        if (current != null && !current.isBlank()) {
            list.addAll(Arrays.asList(current.split(",")));
        }
        for (String a : algorithms) {
            // Only add algorithms this JSch still implements
            if (!list.contains(a) && JSch.getConfig(a) != null) {
                list.add(a);
            }
        }
        JSch.setConfig(key, String.join(",", list));
    }

    /*
     * Config parsing. Names are matched ignoring case, spaces, dashes and
     * underscores, so "MaxMiller", "max miller" and "MAX_MILLER" all work.
     */
    private static String normalize(String s) {
        return s.trim().replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * "Reader Mode": an ImpinjRfMode name, or any Impinj RF mode number. A
     * number is passed to the reader as-is, so modes that only newer readers
     * have (e.g. on the R700) can be used. connect() checks it against the
     * modes the reader reports.
     *
     * @return the mode number, or null to use the default for the reader
     */
    static Integer parseReaderMode(String s, Integer readerID) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String n = normalize(s);
        Integer mode = null;
        if (n.matches("\\d+")) {
            try {
                mode = Integer.valueOf(n);
            } catch (NumberFormatException ex) {
                mode = null;
            }
        } else {
            ImpinjRfMode m = ImpinjRfMode.fromName(n);
            mode = m == null ? null : m.getValue();
        }

        if (mode == null) {
            logger.warn("Unknown Reader Mode '{}' for reader {}. Using the default reader mode.", s, readerID);
            return null;
        }
        if (mode == ImpinjRfMode.AutoSetCustom.getValue()) {
            logger.warn("Reader {}: AutoSetCustom needs extra settings PikaReader does not support. Using the default reader mode.", readerID);
            return null;
        }
        if (mode == ImpinjRfMode.MaxThroughput.getValue()) {
            logger.warn("Reader {} is set to MaxThroughput. This is usually a poor choice when other readers are nearby.", readerID);
        }
        return mode;
    }

    /**
     * Pick the RF mode to use.
     *
     * The reader's mode list comes from its LLRP RF mode table, which usually
     * leaves out the AutoSet modes even on readers that support them (the
     * R420 does). So AutoSet modes are only checked against the list when the
     * list includes some AutoSet modes.
     *
     * @return the mode, or null to let the reader use its own default
     */
    Integer chooseRfMode(List<Integer> supportedModes) {
        boolean haveList = supportedModes != null && !supportedModes.isEmpty();
        boolean listsAutoSet = haveList && supportedModes.stream().anyMatch(ImpinjRfMode::isAutoSet);

        Integer mode = configuredReaderMode;
        if (mode != null && haveList && !supportedModes.contains(mode)
                && (!ImpinjRfMode.isAutoSet(mode) || listsAutoSet)) {
            logger.warn("Reader {} does not list {} as a supported reader mode. Using the default instead.", readerID, ImpinjRfMode.describe(mode));
            mode = null;
        }
        if (mode != null) {
            return mode;
        }

        // We default to the static fast mode as we have a dynamic tag
        // environment and want a faster read rate at the cost of a slight
        // loss of range. See https://support.impinj.com/hc/en-us/articles/360000046899
        // AutoSetStaticFast is not available on 1 and 2 port readers, so
        // those fall back to AutoSetDenseReader.
        List<ImpinjRfMode> preferred = antennaCount > 2
                ? List.of(ImpinjRfMode.AutoSetStaticFast, ImpinjRfMode.AutoSetDenseReader, ImpinjRfMode.AutoSetDenseReaderDeepScan)
                : List.of(ImpinjRfMode.AutoSetDenseReader, ImpinjRfMode.AutoSetDenseReaderDeepScan);
        if (!listsAutoSet) {
            return preferred.get(0).getValue();
        }
        for (ImpinjRfMode m : preferred) {
            if (supportedModes.contains(m.getValue())) {
                return m.getValue();
            }
        }
        logger.warn("Reader {} lists none of {} as supported. Letting the reader use its own default mode.", readerID, preferred);
        return null;
    }

    /**
     * "Search Mode": an Octane SearchMode name or number. "ReaderDefault" is
     * accepted as an alias for ReaderSelected to match the Zebra readers.
     */
    static SearchMode parseSearchMode(String s, Integer readerID) {
        if (s == null || s.isBlank()) {
            return SearchMode.DualTargetBtoASelect;
        }
        String n = normalize(s);
        if (n.equals("readerdefault")) {
            return SearchMode.ReaderSelected;
        }
        SearchMode mode = null;
        if (n.matches("\\d+")) {
            try {
                mode = SearchMode.valueOf(Integer.parseInt(n));
            } catch (Exception ex) {
                mode = null;
            }
        } else {
            for (SearchMode m : SearchMode.values()) {
                if (normalize(m.name()).equals(n)) {
                    mode = m;
                    break;
                }
            }
        }
        if (mode == null) {
            logger.warn("Unknown Search Mode '{}' for reader {}. Using DualTargetBtoASelect.", s, readerID);
            return SearchMode.DualTargetBtoASelect;
        }
        return mode;
    }

    /**
     * "Clock": "Local" (default; "Pi" and "Delta" are accepted as aliases) or "NTP".
     *
     * @return true for NTP
     */
    static boolean parseUseNTP(String s, Integer readerID) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String v = s.trim();
        if (v.equalsIgnoreCase("NTP")) {
            return true;
        }
        if (!v.equalsIgnoreCase("Local") && !v.equalsIgnoreCase("Pi") && !v.equalsIgnoreCase("Delta")) {
            logger.warn("Unknown \"Clock\" setting '{}' for reader {}. Using Local (PikaReader sets the reader's clock from the local clock).", s, readerID);
        }
        return false;
    }

    private static int clamp(Integer readerID, String key, int value, int min, int max) {
        if (value < min || value > max) {
            int v = Math.max(min, Math.min(max, value));
            logger.warn("Reader {}: \"{}\" must be between {} and {}. Using {}.", readerID, key, min, max, v);
            return v;
        }
        return value;
    }

    @Override
    public Boolean isConnected() {
        
        if (!reader.isConnected()) Thread.startVirtualThread(() -> this.connect());
        
        return connected;
    }

    protected Boolean connect() {
        

        //reader = new ImpinjReader();
        if (!reader.isConnected() && reader_command_semaphore.tryAcquire()) {
                
            try {
                logger.info("Connecting to {}:{}{}", readerIP, readerPort, useTls ? " (TLS)" : "");
                if (useTls) {
                    // Leave room for the TLS handshake
                    reader.setConnectTimeout(10000);
                }
                reader.connect(readerIP, readerPort, useTls);

                FeatureSet features = reader.queryFeatureSet();
                antennaCount = Long.valueOf(features.getAntennaCount()).intValue();
                List<Integer> supportedModes = features.getRfModes();
                logger.info("Impinj reader {}: {} ({}) firmware {}, {} antenna ports, supported reader modes: {}",
                        readerID, features.getModelName(), features.getReaderModel(), features.getFirmwareVersion(), antennaCount,
                        supportedModes == null ? "unknown" : supportedModes.stream().map(ImpinjRfMode::describe).toList());

                // The reader may have rebooted or had its clock changed while we were away
                clock.reset();

                // Make sure the reader's time is correct
                if (useNTP) {
                    logNtpStatus();
                }
                setClock();

                reader.setReaderStartListener(new ImpinjReaderStartStopListener(this));
                reader.setReaderStopListener(new ImpinjReaderStartStopListener(this));

                Settings settings = reader.queryDefaultSettings();
                //logger.info("Current Reader Mode: {}", settings.getReaderMode().toString());
                //logger.info("Current Reader Search Mode: {}", settings.getSearchMode().toString());

                ReportConfig report = settings.getReport();
                report.setIncludeAntennaPortNumber(true);
                report.setMode(ReportMode.Individual);
                report.setIncludePeakRssi(Boolean.TRUE);
                //report.setIncludePhaseAngle(Boolean.TRUE);
                report.setIncludeLastSeenTime(Boolean.FALSE);
                report.setIncludeFirstSeenTime(Boolean.TRUE);
                report.setIncludeSeenCount(Boolean.FALSE);
                //settings.setReport(report);

                // Use the configured reader mode if this reader supports it,
                // otherwise the best default it supports (see chooseRfMode).
                // null leaves the choice to the reader.
                Integer mode = chooseRfMode(supportedModes);
                settings.setRfMode(mode);

                // By default we don't want to miss new tags in the 'A' state
                // entering the field when we are inventorying the 'B' tags
                // So we go with the Dual Target B -> A Select mode
                // See https://support.impinj.com/hc/en-us/articles/202756158
                settings.setSearchMode(searchMode);
                settings.setSession(session);
                settings.setTagPopulationEstimate(tagPopulation);

                logger.info("Reader {} inventory: reader mode {}, search mode {}, session {}, tag population {}, clock {}",
                        readerID, ImpinjRfMode.describe(mode), searchMode.name(), session, tagPopulation, useNTP ? "NTP" : "Local");

                // Enable keepalivbes
                settings.getKeepalives().setEnabled(true);
                settings.getKeepalives().setPeriodInMs(3000);
                reader.setKeepaliveListener(new ImpinjKeepaliveTimeoutListener());

                //TODO:  More testing and incorporating the exampels
                // show in the SDK samples DisconnectedOperation.java file
                // Enable Connection Monitoring
                settings.getKeepalives().setEnableLinkMonitorMode(true);
                settings.getKeepalives().setLinkDownThreshold(5);

                // Antenna Settings
                // Antennas are numbered from 1 -> 4
                AntennaConfigGroup antennas = settings.getAntennas();

                antennas.enableAll();

                // Set the power levels and sensitivity
                powerLevel = ImpinjPowerLevels.getLevel(readerConfig.optString("Power Level"));
                logger.info("Setting power level for reader {} to {} ({})", readerID, powerLevel.getLevel(), powerLevel.getDBm());
                antennas.setIsMaxRxSensitivity(true);
                
                // Set the setIsMaxTxPower to false otherwise 
                // the system ignores the setTxPowerinDbm call. 
                if(ImpinjPowerLevels.MAX.equals(powerLevel)) {
                    antennas.setIsMaxTxPower(true);
                } else {
                    antennas.setIsMaxTxPower(false);
                    antennas.setTxPowerinDbm(powerLevel.getDBm());
                }

                // Enable / Disable antennas based on the reader config
                antennas.enableAll();
                JSONObject antennaConfig = readerConfig.optJSONObject("Antenna Config");
                if (antennaConfig != null) {
                    antennaConfig.keySet().forEach(k -> {
                        if (antennaConfig.optString(k).toLowerCase().startsWith("disable")) try {
                            antennas.getAntenna(Integer.valueOf(k)).setEnabled(false);
                        } catch (Exception ex) {
                            logger.warn("Exception disabling antenna port {} for reader {}", k, readerID);
                        }
                    });
                }
                
               
                reader.setTagReportListener(new ImpinjTagReportListener(readerID, clock));

                
                reader.queryStatus().getAntennaStatusGroup().getAntennaList().forEach(a -> {
                    try {
                        Boolean enabled = antennas.getAntenna(a.getPortNumber()).isEnabled();
                        logger.info(" Antenna Status: Reader: {} Port: {} Enabled: {} Connected: {} ", readerID, a.getPortNumber(), enabled, a.isConnected());
                        String s = enabled ? a.isConnected() ? "Connected" : "Disconnected" : "Disabled";
                        Integer port = (int) a.getPortNumber();
                        antennaStatus.put(port, s);
                    } catch (Exception e) {
                        logger.warn("Er, it did not like port {}", a.getPortNumber());
                    }
                });
                
                reader.setAntennaChangeListener(new ImpinjAntennaChangeListener(this));

                logger.debug("Applying Settings for readerID {}", readerID);
                try {
                    reader.applySettings(settings);
                } catch (OctaneSdkException ex) {
                    if (settings.getRfMode() == null) {
                        throw ex;
                    }
                    // Most likely a reader model whose modes we guessed wrong.
                    // Reading with the reader's own default beats not reading.
                    logger.warn("Reader {} rejected the settings with reader mode {} ({}). Retrying with the reader's own default mode.",
                            readerID, ImpinjRfMode.describe(settings.getRfMode()), ex.getMessage());
                    settings.setRfMode(null);
                    reader.applySettings(settings);
                    logger.info("Reader {} is using its own default reader mode", readerID);
                }

                // set up a listener for connection Lost
                reader.setConnectionLostListener(new ImpinjConnectionLostListener(this));

                connected = true;
                connectHintLogged = false;
                

            } catch (OctaneSdkException ex) {
                logger.error("OctaneSdkExcepton connecting to {}:{}{} with error {}", readerIP, readerPort, useTls ? " (TLS)" : "", ex.getMessage(), ex);
                if (!reader.isConnected() && !connectHintLogged) {
                    // Once per outage: connect is retried every second or so
                    connectHintLogged = true;
                    logger.error("Unable to connect to reader {} at {}:{}. An R700 must have its RFID interface set to LLRP{}.",
                            readerID, readerIP, readerPort, useTls ? ", with LLRP over TLS enabled on the reader" : "");
                }
                reader.disconnect();
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                logger.error("Impinj Connect Exception", ex);
            } finally {
                reader_command_semaphore.release();
            }
        }
        
        return (reader != null && reader.isConnected());

    }

    @Override
    public void startReading() {

        logger.trace("Entering Impinj::startReading()");

        logger.info("Starting the reader with the following config: " + readerConfig.toString(4));

        if (reading) {
            return;
        }

        try {
            reader_command_semaphore.acquire();
            if (connect()) {
                // Make sure the reader's time is correct
                setClock();

                logger.info("Starting reader {}", reader.getAddress());
                reader.start();
                reading = true;
            }

        } catch (OctaneSdkException ex) {
            logger.error("OctaneSdkExcepton", ex);
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            logger.error("OctaneSdkExcepton", ex);
        } finally {
            reader_command_semaphore.release();
        }

        logger.trace("Exiting Impinj::startReading()");

    }

    @Override
    public void stopReading() {

        if (reading) {
            try {
                reader.stop();
            } catch (OctaneSdkException ex) {
                logger.error("OctaneSdkExcepton", ex);
            }
            reading = false;
        }
    }

    @Override
    public Integer getID() {
        return readerID;
    }

    @Override
    public String getIP() {
        return readerIP;
    }

    @Override
    public RFIDReader create(JSONObject config) {
        Impinj newReader = new Impinj();
        newReader.readerID = config.optInt("Index", 1);
        newReader.readerIP = config.optString("IP", "127.0.0.1");
        newReader.useTls = LlrpTls.useTls(config);
        newReader.readerPort = LlrpTls.port(config, newReader.useTls);
        newReader.readerConfig = config;
        if (newReader.useTls) {
            // Before the connect thread below makes the first TLS connection
            LlrpTls.allowLegacyCertificates();
        }

        newReader.configuredReaderMode = parseReaderMode(config.optString("Reader Mode", ""), newReader.readerID);
        newReader.searchMode = parseSearchMode(config.optString("Search Mode", ""), newReader.readerID);
        newReader.session = clamp(newReader.readerID, "Session", config.optInt("Session", 2), 0, 3);
        newReader.tagPopulation = clamp(newReader.readerID, "Tag Population", config.optInt("Tag Population", 128), 1, 65535);
        newReader.useNTP = parseUseNTP(config.optString("Clock", ""), newReader.readerID);
        newReader.clock = new ReaderClock(newReader.readerID, ReaderClock.Mode.READER, newReader.useNTP
                ? "Is NTP configured and working on the reader? Set \"Clock\": \"Local\" to have PikaReader set the reader's clock instead."
                : "PikaReader sets this reader's clock over SSH, so check the log for errors from setting the clock. If the reader uses NTP, set \"Clock\": \"NTP\".");

        logger.info("Created new IMPINJ Reader with the following config: {}", config.toString(4));

        Thread.startVirtualThread(() -> newReader.connect());
        return newReader;
    }

    void setReading(boolean b) {
        reading = b;
    }

    @Override
    public Map<Integer, String> getAntennaStatus() {
        long now = Instant.now().getEpochSecond();
        
        logger.trace("Starting getAntennaStatus(): Last antenna check was at {}",lastAntennaUpdateTS);
        
        // The impinj reader will not report on antenna change status while the 
        // unit is not in read mode. But it will when we explicitly call a 
        // reader.queryStatus().getAntennaStatusGroup()
        // So we do just that every 5 seconds just to force its hand. 
        // The actual tracking of any changes is handled by the ImpinjAntennaChangeListener callback
        if ((now - lastAntennaUpdateTS) > 5 && !reading && connected && reader_command_semaphore.tryAcquire()){
            try {
                logger.trace("It has been more than 5 seconds, Triggering an antenna check...");
                reader.queryStatus().getAntennaStatusGroup().getAntennaList();//.forEach(a -> {
//                    try {                        
//                        logger.trace(" Antenna Status: Reader: {} Port: {} Connected: {} ", readerID, a.getPortNumber(), a.isConnected());
//                        String s = a.isConnected() ? "Connected" : "Disconnected";
//                        Integer port = (int) a.getPortNumber();
//                        if (! "Disabled".equals(antennaStatus.get(port)) && ! s.equals(antennaStatus.get(port))) {
//                            logger.info("Impinj Antenna status changed!: Port: {} Old: {} New: {}", port,antennaStatus.get(port), s);
//                            antennaStatus.put(port, s);
//                        }
//                    } catch (Exception e) {
//                        logger.warn("Er, it did not like port {}", a.getPortNumber());
//                    }
//                });
            } catch (OctaneSdkException ex) {
                logger.warn("Er, it did not like querying the status of the reader!");
            }
            reader_command_semaphore.release();
            
            lastAntennaUpdateTS = now; 
        }
        logger.trace("Exiting getAntennaStatus: Last antenna check was at {}",lastAntennaUpdateTS);     
        
        return antennaStatus;

    }

}
