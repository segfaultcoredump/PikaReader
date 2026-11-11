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
package org.pikatimer.pikareader.readers.impinj;

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
import org.pikatimer.pikareader.readers.LlrpTls;
import org.pikatimer.pikareader.readers.RFIDReader;
import org.pikatimer.pikareader.readers.ReaderClock;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.readers.profiles.ReaderProfile;
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
import java.util.Objects;
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

    // What the reader told us on the last connect
    private volatile FeatureSet features = null;
    // The profile settings last applied to the reader (see configure()), and
    // the RF mode the reader accepted (null = the reader's own default)
    private volatile ImpinjProfile activeProfile = null;
    private volatile Integer appliedMode = null;

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
     * have (e.g. on the R700) can be used. Used for a profile's "Reader Mode"
     * override.
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

    static int clamp(Integer readerID, String key, int value, int min, int max) {
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

                FeatureSet f = reader.queryFeatureSet();
                features = f;
                antennaCount = Long.valueOf(f.getAntennaCount()).intValue();
                List<Integer> supportedModes = f.getRfModes();
                logger.info("Impinj reader {}: {} ({}) firmware {}, {} antenna ports, region: {}, supported reader modes: {}",
                        readerID, f.getModelName(), f.getReaderModel(), f.getFirmwareVersion(), antennaCount, describeRegion(f),
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
                reader.setKeepaliveListener(new ImpinjKeepaliveTimeoutListener());
                reader.setTagReportListener(new ImpinjTagReportListener(readerID, clock));
                reader.setAntennaChangeListener(new ImpinjAntennaChangeListener(this));

                // Inventory, antenna and report settings for the current profile
                configure();

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

    /**
     * Build and apply the reader settings for the current profile. The caller
     * holds reader_command_semaphore, and the reader is connected and not
     * reading.
     */
    private void configure() throws OctaneSdkException {
        ProfileManager profiles = ProfileManager.getInstance();
        ReaderProfile profile = profiles.getCurrent();
        FeatureSet f = features;
        List<Integer> supportedModes = f == null ? null : f.getRfModes();

        ImpinjProfile p = ImpinjProfile.resolve(profiles, profile, readerConfig, readerID,
                f == null ? null : f.getReaderModel(), detectedRegion(f), describeRegion(f));

        Settings settings = reader.queryDefaultSettings();

        ReportConfig report = settings.getReport();
        report.setIncludeAntennaPortNumber(true);
        report.setMode(ReportMode.Individual);
        report.setIncludePeakRssi(Boolean.TRUE);
        //report.setIncludePhaseAngle(Boolean.TRUE);
        report.setIncludeLastSeenTime(Boolean.FALSE);
        report.setIncludeFirstSeenTime(Boolean.TRUE);
        report.setIncludeSeenCount(Boolean.FALSE);

        // The profiles use Dual Target B -> A Select by default, so new tags
        // entering the field in the 'A' state aren't missed while we are
        // inventorying the 'B' tags.
        // See https://support.impinj.com/hc/en-us/articles/202756158
        settings.setSearchMode(p.searchMode());
        settings.setSession(p.session());
        settings.setTagPopulationEstimate(p.tagPopulation());
        // Keep inventorying even when no tags are in the field
        settings.getLowDutyCycle().setIsEnabled(false);

        // Enable keepalives
        settings.getKeepalives().setEnabled(true);
        settings.getKeepalives().setPeriodInMs(3000);

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
        powerLevel = p.powerLevel();
        logger.info("Setting power level for reader {} to {} ({})", readerID, powerLevel.getLevel(), powerLevel.getDBm());
        antennas.setIsMaxRxSensitivity(true);

        // Set the setIsMaxTxPower to false otherwise
        // the system ignores the setTxPowerinDbm call.
        if (ImpinjPowerLevels.MAX.equals(powerLevel)) {
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

        logger.debug("Applying Settings for readerID {}", readerID);
        Integer mode = applySettings(settings, p, supportedModes);
        p = p.withFallbackModes(offeredFallbackModes(p, supportedModes, mode));
        activeProfile = p;
        appliedMode = mode;

        logger.info("Reader {} {} profile: reader mode {}, search mode {}, session {}, tag population {}, power {}, clock {} (settings from: {})",
                readerID, profile.name(), ImpinjRfMode.describe(mode), p.searchMode().name(), p.session(), p.tagPopulation(),
                powerLevel.getLevel(), useNTP ? "NTP" : "Local", p.sources());
        if (!p.fallbackModes().isEmpty()) {
            logger.info("Reader {} {} profile fallback reader modes (use one with a \"Reader Mode\" override): {}",
                    readerID, profile.name(), p.fallbackModes().stream().map(ImpinjRfMode::describe).toList());
        }

        verifySettings(p, mode);
    }

    /**
     * Apply the settings with the profile's first RF mode the reader accepts.
     * If it accepts none of them, use the reader's own default mode, since
     * reading with it beats not reading.
     *
     * @return the RF mode in use, or null for the reader's default
     */
    private Integer applySettings(Settings settings, ImpinjProfile p, List<Integer> supportedModes) throws OctaneSdkException {
        List<Integer> candidates = p.rfModes();
        boolean haveList = supportedModes != null && !supportedModes.isEmpty();
        // The LLRP mode table usually leaves out the AutoSet modes even on
        // readers that support them (the R420 does)
        boolean listsAutoSet = haveList && supportedModes.stream().anyMatch(ImpinjRfMode::isAutoSet);

        for (int i = 0; i < candidates.size(); i++) {
            Integer mode = candidates.get(i);
            if (haveList && !supportedModes.contains(mode) && (!ImpinjRfMode.isAutoSet(mode) || listsAutoSet)) {
                logger.warn("Reader {} does not list reader mode {} as supported. Trying it anyway.", readerID, ImpinjRfMode.describe(mode));
            }
            settings.setRfMode(mode);
            try {
                reader.applySettings(settings);
                if (i > 0) {
                    logger.info("Reader {} accepted reader mode {} for the {} profile", readerID, ImpinjRfMode.describe(mode), p.profile().name());
                }
                return mode;
            } catch (OctaneSdkException ex) {
                logger.warn("Reader {} rejected reader mode {} ({}).{}", readerID, ImpinjRfMode.describe(mode), ex.getMessage(),
                        i + 1 < candidates.size() ? " Trying " + ImpinjRfMode.describe(candidates.get(i + 1)) + "." : "");
            }
        }

        if (!candidates.isEmpty()) {
            logger.error("Reader {} accepted none of the reader modes {} for the {} profile. Using the reader's own default mode instead.",
                    readerID, candidates.stream().map(ImpinjRfMode::describe).toList(), p.profile().name());
        }
        settings.setRfMode(null);
        reader.applySettings(settings);
        return null;
    }

    /**
     * Fallback modes are only offered to the operator, so drop the one the
     * reader is already using (e.g. an R220 that fell through to mode 2) and
     * the ones this reader doesn't list. LLRP modes 0-5 are kept: newer
     * readers map them to their own regional modes.
     */
    private List<Integer> offeredFallbackModes(ImpinjProfile p, List<Integer> supportedModes, Integer appliedMode) {
        boolean haveList = supportedModes != null && !supportedModes.isEmpty();
        List<Integer> kept = new ArrayList<>();
        for (Integer m : p.fallbackModes()) {
            if (m.equals(appliedMode)) {
                logger.debug("Reader {}: fallback reader mode {} is the mode already in use", readerID, ImpinjRfMode.describe(m));
            } else if (!haveList || m <= 5 || supportedModes.contains(m)) {
                kept.add(m);
            } else {
                logger.info("Reader {}: not offering fallback reader mode {} for the {} profile, since the reader does not list it",
                        readerID, ImpinjRfMode.describe(m), p.profile().name());
            }
        }
        return kept;
    }

    /**
     * Read the settings back from the reader and log anything that differs
     * from what was applied.
     */
    private void verifySettings(ImpinjProfile p, Integer mode) {
        try {
            Settings applied = reader.querySettings();
            List<String> mismatches = new ArrayList<>();
            if (applied.getSession() != p.session()) {
                mismatches.add("session " + applied.getSession() + " (applied " + p.session() + ")");
            }
            if (applied.getSearchMode() != p.searchMode()) {
                mismatches.add("search mode " + applied.getSearchMode() + " (applied " + p.searchMode() + ")");
            }
            if (applied.getTagPopulationEstimate() != p.tagPopulation()) {
                mismatches.add("tag population " + applied.getTagPopulationEstimate() + " (applied " + p.tagPopulation() + ")");
            }
            if (!mismatches.isEmpty()) {
                logger.warn("Reader {} reports different settings than PikaReader applied: {}", readerID, mismatches);
            }
            if (mode != null && !Objects.equals(applied.getRfMode(), mode)) {
                // Newer readers report the regional mode an LLRP mode maps to
                logger.info("Reader {} reports reader mode {} (applied {})", readerID,
                        ImpinjRfMode.describe(applied.getRfMode()), ImpinjRfMode.describe(mode));
            }
            if (mismatches.isEmpty()) {
                logger.debug("Reader {} settings read back OK", readerID);
            }
        } catch (Exception ex) {
            logger.debug("Reader {}: unable to read back the settings: {}", readerID, ex.getMessage());
        }
    }

    private static String detectedRegion(FeatureSet f) {
        if (f == null) {
            return null;
        }
        return ProfileManager.regionFamily(f.getCommunicationsStandard() == null ? null : f.getCommunicationsStandard().intValue(),
                f.getTxFrequencies());
    }

    private static String describeRegion(FeatureSet f) {
        if (f == null) {
            return "unknown";
        }
        List<Double> freqs = f.getTxFrequencies();
        String fr = freqs == null || freqs.isEmpty() ? "no frequency list"
                : String.format(Locale.ROOT, "%d frequencies %.2f-%.2f", freqs.size(),
                        freqs.stream().mapToDouble(Double::doubleValue).min().orElse(0),
                        freqs.stream().mapToDouble(Double::doubleValue).max().orElse(0));
        return "communications standard " + f.getCommunicationsStandard() + ", " + fr;
    }

    @Override
    public void applyProfile() {
        String name = ProfileManager.getInstance().getCurrent().name();
        if (reading || !reader.isConnected()) {
            logger.info("Reader {} will use the {} profile when it next connects", readerID, name);
            return;
        }
        try {
            // The SDK reports connected while a connect is still in progress,
            // so this can wait for that connect to finish. It may have
            // applied the previous profile, so apply the current one again.
            reader_command_semaphore.acquire();
            try {
                if (reader.isConnected() && !reading) {
                    configure();
                } else {
                    logger.info("Reader {} will use the {} profile when it next connects", readerID, name);
                }
            } finally {
                reader_command_semaphore.release();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            logger.error("Reader {}: unable to apply the {} profile: {}", readerID, ProfileManager.getInstance().getCurrent().name(), ex.getMessage(), ex);
        }
    }

    @Override
    public JSONObject getProfileSettings() {
        ImpinjProfile p = activeProfile;
        ReaderProfile current = ProfileManager.getInstance().getCurrent();
        if (p != null && p.profile().equals(current)) {
            JSONObject o = p.toJSON(appliedMode);
            o.put("applied", true);
            return o;
        }
        JSONObject o = new JSONObject();
        o.put("profile", current.id());
        o.put("applied", false);
        o.put("note", "The profile is applied when the reader connects");
        return o;
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

        // Inventory settings come from the selected profile (see configure())
        ProfileManager.getInstance().checkReaderConfig(config, newReader.readerID, ImpinjProfile.OVERRIDE_KEYS);
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
