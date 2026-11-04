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
import com.impinj.octane.ReaderMode;
import com.impinj.octane.ReportConfig;
import com.impinj.octane.ReportMode;
import com.impinj.octane.RshellEngine;
import com.impinj.octane.SearchMode;
import com.impinj.octane.Settings;
import com.pikatimer.pikareader.readers.RFIDReader;
import com.pikatimer.pikareader.readers.ReaderClock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private final ImpinjReader reader;
    private ImpinjPowerLevels powerLevel = ImpinjPowerLevels.HIGH; // 30.0dBm / 1.0W. A go-to value for most stuff. 

    private Map<Integer, String> antennaStatus = new HashMap<>();

    private JSONObject readerConfig = new JSONObject();

    private String readerIP;
    private Integer readerID;
    private Boolean reading = false;
    private Boolean connected = false;
    
    private Semaphore reader_command_semaphore = new Semaphore(1);

    private Integer antennaCount = 0;
    private long lastAntennaUpdateTS = 0L;

    // Inventory settings from the config. A null reader mode means "pick the
    // default for this reader" (see connect()).
    private ReaderMode configuredReaderMode = null;
    private SearchMode searchMode = SearchMode.DualTargetBtoASelect;
    private int session = 2; // the Octane SDK default
    private int tagPopulation = 128;

    // "Clock": "Pi" (default) sets the reader's clock from the Pi over SSH.
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
            //rshell.open(readerIP, "root", "impinj", 10000, RShellConnectionType.Telnet);

            // make sure NTP is disabled
            String cmd = "config network ntp disable";
            logger.debug("Sending command '" + cmd + "' to " + readerIP);

            String reply = rshell.send(cmd);

            logger.trace("Raw Reply");
            logger.trace(reply);

            // Issue the command as close to the turn of the second as possible 
            // We fudge the time by a 10th of a second to account for the latency of the set command. 
            int gap = 0;
            while (gap < Instant.now().plusNanos(100000000).getNano()) {
                gap = Instant.now().plusNanos(100000000).getNano();
                Thread.sleep(10);
            }

            logger.debug("{} setClock() Start Timestamp: {}", readerIP, Instant.now());

            cmd = "config system time " + OffsetDateTime.now(ZoneOffset.UTC).plusNanos(100000000).format(formatter);
            logger.debug("Sending command '" + cmd + "' to " + readerIP);

            reply = rshell.send(cmd);

            logger.trace("Raw Reply");
            logger.trace(reply);
            logger.debug("{} setClock() Finish Timestamp: {}", readerIP, Instant.now());

            rshell.close();
            logger.info("Clock set for {}", readerIP);

            // Older samples were taken against the old clock
            clock.reset();

        } catch (OctaneSdkException ex) {
            logger.error("OctaneSdkExcepton", ex);
        } catch (Exception ex) {
            logger.error("Exception", ex);
        }

        logger.trace("Exiting Impinj::setClock()");

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
            logger.info("Reader {} ({}) NTP status:\n{}", readerID, readerIP, reply);
        } catch (Exception ex) {
            logger.warn("Reader {} ({}): unable to read the NTP status over SSH ({}). The reader's clock will still be used as-is.",
                    readerID, readerIP, ex.getMessage());
        }
    }

    /*
     * Config parsing. Names are matched ignoring case, spaces, dashes and
     * underscores, so "MaxMiller", "max miller" and "MAX_MILLER" all work.
     */
    private static String normalize(String s) {
        return s.trim().replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * "Reader Mode": an Octane ReaderMode name or Impinj mode number.
     *
     * @return the mode, or null to use the default for the reader
     */
    static ReaderMode parseReaderMode(String s, Integer readerID) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String n = normalize(s);
        ReaderMode mode = null;
        if (n.matches("\\d+")) {
            try {
                mode = ReaderMode.valueOf(Integer.parseInt(n));
            } catch (Exception ex) {
                mode = null;
            }
        } else {
            for (ReaderMode m : ReaderMode.values()) {
                if (normalize(m.name()).equals(n)) {
                    mode = m;
                    break;
                }
            }
        }

        if (mode == null) {
            logger.warn("Unknown Reader Mode '{}' for reader {}. Using the default reader mode.", s, readerID);
            return null;
        }
        if (mode == ReaderMode.AutoSetCustom) {
            logger.warn("Reader {}: AutoSetCustom needs extra settings PikaReader does not support. Using the default reader mode.", readerID);
            return null;
        }
        if (mode == ReaderMode.MaxThroughput) {
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
     * "Clock": "Pi" (default, "Delta" is accepted as an alias) or "NTP".
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
        if (!v.equalsIgnoreCase("Pi") && !v.equalsIgnoreCase("Delta")) {
            logger.warn("Unknown \"Clock\" setting '{}' for reader {}. Using Pi (PikaReader sets the reader's clock).", s, readerID);
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
                logger.info("Connecting to {}", readerIP);
                reader.connect(readerIP);

                FeatureSet features = reader.queryFeatureSet();
                antennaCount = Long.valueOf(features.getAntennaCount()).intValue();
                List<ReaderMode> supportedModes = features.getReaderModes();
                logger.info("Impinj reader {}: {} firmware {}, {} antenna ports, supported reader modes: {}",
                        readerID, features.getModelName(), features.getFirmwareVersion(), antennaCount, supportedModes);

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

                // Use the configured reader mode if this reader supports it.
                // The AutoSet modes are not checked because the SDK may not
                // list them, even on readers that support them.
                ReaderMode mode = configuredReaderMode;
                if (mode != null && mode.getValue() < 1000 && supportedModes != null
                        && !supportedModes.isEmpty() && !supportedModes.contains(mode)) {
                    logger.warn("Reader {} does not list {} as a supported reader mode. Using the default instead.", readerID, mode.name());
                    mode = null;
                }
                if (mode == null) {
                    // We will default to the static fast mode
                    // as we have a dynamic tag environment and want a faster
                    // read rate at the cost of a slight loss of range
                    // See https://support.impinj.com/hc/en-us/articles/360000046899
                    // AutoSetStaticFast is not available on 1 and 2 port readers
                    // so fall back to AutoSetDenseReader
                    mode = antennaCount > 2 ? ReaderMode.AutoSetStaticFast : ReaderMode.AutoSetDenseReader;
                }
                settings.setReaderMode(mode);

                // By default we don't want to miss new tags in the 'A' state
                // entering the field when we are inventorying the 'B' tags
                // So we go with the Dual Target B -> A Select mode
                // See https://support.impinj.com/hc/en-us/articles/202756158
                settings.setSearchMode(searchMode);
                settings.setSession(session);
                settings.setTagPopulationEstimate(tagPopulation);

                logger.info("Reader {} inventory: reader mode {}, search mode {}, session {}, tag population {}, clock {}",
                        readerID, mode.name(), searchMode.name(), session, tagPopulation, useNTP ? "NTP" : "Pi");

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
                reader.applySettings(settings);

                // set up a listener for connection Lost
                reader.setConnectionLostListener(new ImpinjConnectionLostListener(this));

                connected = true;
                

            } catch (OctaneSdkException ex) {
                logger.error("OctaneSdkExcepton connecting to {} with error {}", readerIP, ex.getMessage(), ex);
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
        newReader.readerConfig = config;

        newReader.configuredReaderMode = parseReaderMode(config.optString("Reader Mode", ""), newReader.readerID);
        newReader.searchMode = parseSearchMode(config.optString("Search Mode", ""), newReader.readerID);
        newReader.session = clamp(newReader.readerID, "Session", config.optInt("Session", 2), 0, 3);
        newReader.tagPopulation = clamp(newReader.readerID, "Tag Population", config.optInt("Tag Population", 128), 1, 65535);
        newReader.useNTP = parseUseNTP(config.optString("Clock", ""), newReader.readerID);
        newReader.clock = new ReaderClock(newReader.readerID, ReaderClock.Mode.READER, newReader.useNTP
                ? "Is NTP configured and working on the reader? Set \"Clock\": \"Pi\" to have PikaReader set the reader's clock instead."
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
