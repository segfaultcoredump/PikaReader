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
package com.pikatimer.pikareader.readers.zebra;

import com.google.auto.service.AutoService;
import com.pikatimer.pikareader.readers.RFIDReader;
import com.pikatimer.pikareader.readers.ReaderClock;
import com.pikatimer.pikareader.readers.impinj.ImpinjPowerLevels;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.mina.core.session.IoSession;
import org.json.JSONObject;
import org.llrp.ltk.generated.enumerations.AISpecStopTriggerType;
import org.llrp.ltk.generated.enumerations.AirProtocols;
import org.llrp.ltk.generated.enumerations.C1G2TagInventoryStateAwareI;
import org.llrp.ltk.generated.enumerations.C1G2TagInventoryStateAwareS;
import org.llrp.ltk.generated.enumerations.GetReaderCapabilitiesRequestedData;
import org.llrp.ltk.generated.enumerations.GetReaderConfigRequestedData;
import org.llrp.ltk.generated.enumerations.KeepaliveTriggerType;
import org.llrp.ltk.generated.enumerations.NotificationEventType;
import org.llrp.ltk.generated.enumerations.ROReportTriggerType;
import org.llrp.ltk.generated.enumerations.ROSpecStartTriggerType;
import org.llrp.ltk.generated.enumerations.ROSpecState;
import org.llrp.ltk.generated.enumerations.ROSpecStopTriggerType;
import org.llrp.ltk.generated.enumerations.StatusCode;
import org.llrp.ltk.generated.interfaces.AirProtocolUHFRFModeTable;
import org.llrp.ltk.generated.messages.ADD_ROSPEC;
import org.llrp.ltk.generated.messages.ADD_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.messages.DELETE_ACCESSSPEC;
import org.llrp.ltk.generated.messages.DELETE_ACCESSSPEC_RESPONSE;
import org.llrp.ltk.generated.messages.DELETE_ROSPEC;
import org.llrp.ltk.generated.messages.DELETE_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.messages.ENABLE_EVENTS_AND_REPORTS;
import org.llrp.ltk.generated.messages.ENABLE_ROSPEC;
import org.llrp.ltk.generated.messages.ENABLE_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.messages.GET_READER_CAPABILITIES;
import org.llrp.ltk.generated.messages.GET_READER_CAPABILITIES_RESPONSE;
import org.llrp.ltk.generated.messages.GET_READER_CONFIG;
import org.llrp.ltk.generated.messages.GET_READER_CONFIG_RESPONSE;
import org.llrp.ltk.generated.messages.SET_READER_CONFIG;
import org.llrp.ltk.generated.messages.SET_READER_CONFIG_RESPONSE;
import org.llrp.ltk.generated.messages.START_ROSPEC;
import org.llrp.ltk.generated.messages.START_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.messages.STOP_ROSPEC;
import org.llrp.ltk.generated.messages.STOP_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.parameters.AISpec;
import org.llrp.ltk.generated.parameters.AISpecStopTrigger;
import org.llrp.ltk.generated.parameters.AntennaConfiguration;
import org.llrp.ltk.generated.parameters.AntennaProperties;
import org.llrp.ltk.generated.parameters.C1G2EPCMemorySelector;
import org.llrp.ltk.generated.parameters.C1G2InventoryCommand;
import org.llrp.ltk.generated.parameters.C1G2RFControl;
import org.llrp.ltk.generated.parameters.C1G2SingulationControl;
import org.llrp.ltk.generated.parameters.C1G2TagInventoryStateAwareSingulationAction;
import org.llrp.ltk.generated.parameters.C1G2UHFRFModeTable;
import org.llrp.ltk.generated.parameters.C1G2UHFRFModeTableEntry;
import org.llrp.ltk.generated.parameters.EventNotificationState;
import org.llrp.ltk.generated.parameters.EventsAndReports;
import org.llrp.ltk.generated.parameters.FrequencyInformation;
import org.llrp.ltk.generated.parameters.GeneralDeviceCapabilities;
import org.llrp.ltk.generated.parameters.InventoryParameterSpec;
import org.llrp.ltk.generated.parameters.KeepaliveSpec;
import org.llrp.ltk.generated.parameters.LLRPCapabilities;
import org.llrp.ltk.generated.parameters.LLRPStatus;
import org.llrp.ltk.generated.parameters.ParameterError;
import org.llrp.ltk.generated.parameters.RFReceiver;
import org.llrp.ltk.generated.parameters.RFTransmitter;
import org.llrp.ltk.generated.parameters.ROBoundarySpec;
import org.llrp.ltk.generated.parameters.ROReportSpec;
import org.llrp.ltk.generated.parameters.ROSpec;
import org.llrp.ltk.generated.parameters.ROSpecStartTrigger;
import org.llrp.ltk.generated.parameters.ROSpecStopTrigger;
import org.llrp.ltk.generated.parameters.ReaderEventNotificationSpec;
import org.llrp.ltk.generated.parameters.ReceiveSensitivityTableEntry;
import org.llrp.ltk.generated.parameters.RegulatoryCapabilities;
import org.llrp.ltk.generated.parameters.TagReportContentSelector;
import org.llrp.ltk.generated.parameters.TransmitPowerLevelTableEntry;
import org.llrp.ltk.generated.parameters.UHFBandCapabilities;
import org.llrp.ltk.net.LLRPConnectionAttemptFailedException;
import org.llrp.ltk.net.LLRPConnector;
import org.llrp.ltk.net.LLRPIoHandlerAdapterImpl;
import org.llrp.ltk.types.Bit;
import org.llrp.ltk.types.LLRPMessage;
import org.llrp.ltk.types.TwoBitField;
import org.llrp.ltk.types.UnsignedByte;
import org.llrp.ltk.types.UnsignedInteger;
import org.llrp.ltk.types.UnsignedShort;
import org.llrp.ltk.types.UnsignedShortArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zebra / Motorola FX series (FX7400, FX7500, FX9500, FX9600) reader support
 * using plain LLRP via the LTK classes bundled in the Impinj Octane SDK. No
 * Zebra SDK is needed, so this runs anywhere Java does.
 *
 * Everything model-specific (port count, power table, RF mode table, clock
 * capability) is read from GET_READER_CAPABILITIES when we connect, so the
 * same code drives every FX model.
 *
 * Each configured reader gets its own instance with its own connection, IO
 * thread, locks, clock offset and monitor thread.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
@AutoService(RFIDReader.class)
public class Zebra implements RFIDReader {

    private static final Logger logger = LoggerFactory.getLogger(Zebra.class);

    static final int ROSPEC_ID = 1;
    static final int DEFAULT_PORT = 5084;
    private static final int KEEPALIVE_MS = 3000;
    private static final long LINK_TIMEOUT_MS = 3L * KEEPALIVE_MS;
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    private static final long TRANSACT_TIMEOUT_MS = 10_000;
    private static final long PROBE_TIMEOUT_MS = 3_000;
    private static final long ANTENNA_POLL_SECONDS = 5;
    private static final long START_RETRY_NANOS = 5_000_000_000L;
    private static final int MAX_QUICK_ROSPEC_ENDS = 10;

    /**
     * How the inventory rounds target the tags' session flags.
     */
    enum SearchMode {
        // Alternate AISpecs targeting A then B. Tags flipped by neighbouring
        // readers are still seen, and every tag is re-read on each flip.
        DUAL_TARGET("DualTarget"),
        // Target A only. Fewest duplicate reads.
        SINGLE_TARGET("SingleTarget"),
        // Let the reader pick the target (state-unaware inventory)
        READER_DEFAULT("ReaderDefault");

        private final String label;

        SearchMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static SearchMode fromConfig(String s, Integer readerID) {
            if (s == null || s.isBlank()) {
                return DUAL_TARGET;
            }
            String n = s.trim().replace(" ", "").replace("_", "");
            for (SearchMode m : values()) {
                if (m.label.equalsIgnoreCase(n) || m.name().replace("_", "").equalsIgnoreCase(n)) {
                    return m;
                }
            }
            logger.warn("Unknown Search Mode '{}' for reader {}. Using {}.", s, readerID, DUAL_TARGET);
            return DUAL_TARGET;
        }
    }

    static class ZebraException extends Exception {

        // true when the reader answered with an error status, false when
        // the command never got an answer (timeout, link down)
        final boolean rejected;

        ZebraException(String message, boolean rejected) {
            super(message);
            this.rejected = rejected;
        }
    }

    // Configuration
    private Integer readerID = -1;
    private String readerIP = "";
    private int readerPort = DEFAULT_PORT;
    private JSONObject readerConfig = new JSONObject();
    private SearchMode searchMode = SearchMode.DUAL_TARGET;
    private int session = 1;
    private int tagPopulation = 128;
    private int dwellMs = 500;
    private ReaderClock clock = new ReaderClock(-1, ReaderClock.Mode.DELTA, null);
    private final Set<Integer> disabledAntennas = ConcurrentHashMap.newKeySet();

    // What we learned from the reader on the last connect
    private int maxAntennas = 0;
    private List<Integer> enabledAntennas = List.of();
    private Integer powerIndex = null;
    private Integer rxSensitivityIndex = null;
    private Integer modeIdentifier = null;
    private Map<Integer, int[]> hopChannel = new HashMap<>();
    private int[] defaultHopChannel = {1, 1};
    private boolean rospecEventsSupported = true;
    private boolean stateAwareSupported = true;
    private long maxSpecsPerROSpec = 0;
    private volatile SearchMode activeSearchMode = SearchMode.DUAL_TARGET;

    // Live state
    private final Map<Integer, String> antennaStatus = new ConcurrentHashMap<>();
    private volatile LLRPConnector connection;
    private volatile ZebraMessageHandler handler;
    private final ReentrantLock commandLock = new ReentrantLock();
    private final ReentrantLock startStopLock = new ReentrantLock();
    private final Object rospecLock = new Object();
    private volatile boolean connected = false;
    private volatile boolean reading = false;
    private volatile boolean wantReading = false;
    private volatile String linkError = null;
    private volatile long lastAntennaPollSec = 0;
    private volatile long lastStartNanos = 0;
    private volatile long lastStartAttemptNanos = 0;
    private int quickROSpecEnds = 0;
    private boolean warnedNoKeepalives = false;
    private ZebraConnectionMonitor monitor;

    public Zebra() {
        // The ServiceLoader creates one of these just to call create().
        // Keep it cheap: no connections and no threads here.
    }

    @Override
    public String getType() {
        return "ZEBRA";
    }

    @Override
    public RFIDReader create(JSONObject config) {
        Zebra z = new Zebra();
        z.readerID = config.optInt("Index", 1);
        z.readerIP = config.optString("IP", "127.0.0.1");
        z.readerPort = config.optInt("Port", DEFAULT_PORT);
        z.readerConfig = config;
        z.searchMode = SearchMode.fromConfig(config.optString("Search Mode", ""), z.readerID);
        z.activeSearchMode = z.searchMode;
        z.session = clamp(z.readerID, "Session", config.optInt("Session", 1), 0, 3);
        z.tagPopulation = clamp(z.readerID, "Tag Population", config.optInt("Tag Population", 128), 1, 65535);
        z.dwellMs = clamp(z.readerID, "Dwell", config.optInt("Dwell", 500), 50, 60000);
        z.clock = new ReaderClock(z.readerID, clockModeFromConfig(config.optString("Clock", ""), z.readerID),
                "Is NTP configured and working on the reader? Set \"Clock\": \"Delta\" to use the Pi's clock instead.");

        JSONObject antennaConfig = config.optJSONObject("Antenna Config");
        if (antennaConfig != null) {
            antennaConfig.keySet().forEach(k -> {
                if (antennaConfig.optString(k).toLowerCase().startsWith("disable")) {
                    try {
                        z.disabledAntennas.add(Integer.valueOf(k.trim()));
                    } catch (NumberFormatException ex) {
                        logger.warn("Ignoring Antenna Config entry '{}' for reader {}: not a port number", k, z.readerID);
                    }
                }
            });
        }

        logger.info("Created new ZEBRA Reader with the following config: {}", config.toString(4));

        z.monitor = new ZebraConnectionMonitor(z);
        Thread.ofVirtual().name("Zebra-" + z.readerID + "-Monitor").start(z.monitor);
        return z;
    }

    /**
     * "Clock": "NTP" trusts the reader's own clock. "Delta" (the default, with
     * "Pi" as an alias) corrects every read to the Pi's clock.
     */
    static ReaderClock.Mode clockModeFromConfig(String s, Integer readerID) {
        if (s == null || s.isBlank()) {
            return ReaderClock.Mode.DELTA;
        }
        String v = s.trim();
        if (v.equalsIgnoreCase("NTP")) {
            return ReaderClock.Mode.READER;
        }
        if (!v.equalsIgnoreCase("Delta") && !v.equalsIgnoreCase("Pi")) {
            logger.warn("Unknown \"Clock\" setting '{}' for reader {}. Using Delta (Pi time).", s, readerID);
        }
        return ReaderClock.Mode.DELTA;
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
    public Integer getID() {
        return readerID;
    }

    @Override
    public String getIP() {
        return readerIP;
    }

    @Override
    public Boolean isReading() {
        return reading;
    }

    @Override
    public Boolean isConnected() {
        // Reconnects are driven by the ZebraConnectionMonitor
        return connected;
    }

    @Override
    public String getStatus() {
        return reading ? "Reading" : connected ? "Connected" : "Disconnected";
    }

    boolean wantsToRead() {
        return wantReading;
    }

    /*
     * Connection setup
     */
    boolean connect() {
        if (connected) {
            return true;
        }
        if (!commandLock.tryLock()) {
            // Somebody else is already talking to (or connecting to) the reader
            return connected;
        }
        try {
            if (connected) {
                return true;
            }
            return doConnect();
        } finally {
            commandLock.unlock();
        }
    }

    private boolean doConnect() {
        logger.info("Connecting to Zebra reader {} at {}:{}", readerID, readerIP, readerPort);

        clock.reset();
        linkError = null;

        ZebraMessageHandler h = new ZebraMessageHandler(this, new ZebraTagReportHandler(this, clock), clock);
        LLRPConnector c = new LLRPConnector(h, readerIP, readerPort) {
            @Override
            public boolean reconnect() {
                // The LTK will try to silently reconnect from inside send() and
                // transact() when the session drops. We manage reconnects ourselves.
                return false;
            }
        };
        // The stock LTK IO handler never reports a closed session, so a reader
        // reboot would only be noticed when keepalives stop. Hook it.
        LLRPIoHandlerAdapterImpl ioHandler = new LLRPIoHandlerAdapterImpl(c) {
            @Override
            public void sessionClosed(IoSession session) throws Exception {
                super.sessionClosed(session);
                h.errorOccured("LLRP session closed");
            }
        };
        ioHandler.setKeepAliveForward(true);
        c.setHandler(ioHandler);
        handler = h;
        connection = c;

        try {
            c.connect(CONNECT_TIMEOUT_MS);

            readCapabilities();
            configureReader();

            // Clear out anything left over from a previous session or another client
            DELETE_ACCESSSPEC deleteAccess = new DELETE_ACCESSSPEC();
            deleteAccess.setAccessSpecID(new UnsignedInteger(0));
            transactIgnoringRejection(deleteAccess, DELETE_ACCESSSPEC_RESPONSE.class);
            DELETE_ROSPEC deleteROSpec = new DELETE_ROSPEC();
            deleteROSpec.setROSpecID(new UnsignedInteger(0));
            transactIgnoringRejection(deleteROSpec, DELETE_ROSPEC_RESPONSE.class);

            readReaderConfig();
            addROSpec();

            // ENABLE_EVENTS_AND_REPORTS has no response, so just send it
            c.send(new ENABLE_EVENTS_AND_REPORTS());

            h.touch();
            warnedNoKeepalives = false;
            connected = true;
            logger.info("Connected to Zebra reader {} at {}:{}", readerID, readerIP, readerPort);

            setClock();
            return true;

        } catch (LLRPConnectionAttemptFailedException ex) {
            logger.error("Unable to connect to Zebra reader {} at {}:{}: {}", readerID, readerIP, readerPort, ex.getMessage());
            if (ex.getMessage() != null && ex.getMessage().contains("Already_Exists")) {
                logger.error("Another LLRP client is already connected to reader {}. Disconnect it and PikaReader will keep retrying.", readerID);
            }
        } catch (ZebraException ex) {
            logger.error("Unable to configure Zebra reader {}: {}", readerID, ex.getMessage());
        } catch (Exception ex) {
            // MINA wraps the useful part ("Connection refused", "No route to host")
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            logger.error("Unable to connect to Zebra reader {} at {}:{}: {}", readerID, readerIP, readerPort,
                    root.getMessage() != null ? root.getMessage() : root.toString());
            logger.debug("Connect exception", ex);
        }

        teardown();
        return false;
    }

    private void readCapabilities() throws ZebraException {
        GET_READER_CAPABILITIES request = new GET_READER_CAPABILITIES();
        request.setRequestedData(new GetReaderCapabilitiesRequestedData(GetReaderCapabilitiesRequestedData.All));
        GET_READER_CAPABILITIES_RESPONSE caps = transact(request, GET_READER_CAPABILITIES_RESPONSE.class);

        maxAntennas = 0;
        boolean hasUTCClock = false;
        List<ReceiveSensitivityTableEntry> rxTable = List.of();

        GeneralDeviceCapabilities general = caps.getGeneralDeviceCapabilities();
        if (general != null) {
            maxAntennas = general.getMaxNumberOfAntennaSupported() == null ? 0 : general.getMaxNumberOfAntennaSupported().intValue();
            hasUTCClock = general.getHasUTCClockCapability() != null && general.getHasUTCClockCapability().toBoolean();
            if (general.getReceiveSensitivityTableEntryList() != null) {
                rxTable = general.getReceiveSensitivityTableEntryList();
            }
            logger.info("Zebra reader {}: manufacturer {} model {} firmware {}, {} antenna ports, UTC clock: {}",
                    readerID, general.getDeviceManufacturerName(), general.getModelName(),
                    general.getReaderFirmwareVersion(), maxAntennas, hasUTCClock);
        }

        if (!hasUTCClock && clock.getMode() == ReaderClock.Mode.READER) {
            logger.warn("Reader {} does not report a UTC clock, so NTP clock mode may not work. Consider \"Clock\": \"Delta\".", readerID);
        }

        // What the inventory strategies need: state-aware singulation for
        // SingleTarget/DualTarget, and two specs per ROSpec for DualTarget
        stateAwareSupported = true;
        maxSpecsPerROSpec = 0;
        LLRPCapabilities llrp = caps.getLLRPCapabilities();
        if (llrp != null) {
            stateAwareSupported = llrp.getCanDoTagInventoryStateAwareSingulation() == null
                    || llrp.getCanDoTagInventoryStateAwareSingulation().toBoolean();
            maxSpecsPerROSpec = llrp.getMaxNumSpecsPerROSpec() == null ? 0 : llrp.getMaxNumSpecsPerROSpec().toLong();
            logger.info("Zebra reader {}: state-aware singulation: {}, max specs per ROSpec: {}",
                    readerID, stateAwareSupported, maxSpecsPerROSpec == 0 ? "not stated" : maxSpecsPerROSpec);
        }

        List<TransmitPowerLevelTableEntry> powerTable = List.of();
        List<C1G2UHFRFModeTableEntry> modeTable = new ArrayList<>();
        FrequencyInformation frequencyInformation = null;

        RegulatoryCapabilities regulatory = caps.getRegulatoryCapabilities();
        if (regulatory != null) {
            logger.info("Zebra reader {}: country code {}, communications standard {}",
                    readerID, regulatory.getCountryCode(), regulatory.getCommunicationsStandard());
            UHFBandCapabilities uhf = regulatory.getUHFBandCapabilities();
            if (uhf != null) {
                if (uhf.getTransmitPowerLevelTableEntryList() != null) {
                    powerTable = uhf.getTransmitPowerLevelTableEntryList();
                }
                frequencyInformation = uhf.getFrequencyInformation();
                if (uhf.getAirProtocolUHFRFModeTableList() != null) {
                    for (AirProtocolUHFRFModeTable t : uhf.getAirProtocolUHFRFModeTableList()) {
                        if (t instanceof C1G2UHFRFModeTable c1g2 && c1g2.getC1G2UHFRFModeTableEntryList() != null) {
                            modeTable.addAll(c1g2.getC1G2UHFRFModeTableEntryList());
                        }
                    }
                }
            }
        }

        modeTable.forEach(m -> logger.info("Zebra reader {} RF {}", readerID, ZebraReaderMode.describe(m)));
        C1G2UHFRFModeTableEntry mode = ZebraReaderMode.select(readerConfig.optString("Reader Mode", ""), modeTable, readerID);
        modeIdentifier = mode == null ? null : mode.getModeIdentifier().intValue();

        powerIndex = selectPowerIndex(powerTable);
        rxSensitivityIndex = selectRxSensitivityIndex(rxTable);

        // Regional channel defaults in case the reader doesn't tell us its current ones
        defaultHopChannel = new int[]{1, 1};
        if (frequencyInformation != null && frequencyInformation.getHopping() != null && frequencyInformation.getHopping().toBoolean()
                && frequencyInformation.getFrequencyHopTableList() != null && !frequencyInformation.getFrequencyHopTableList().isEmpty()) {
            defaultHopChannel[0] = frequencyInformation.getFrequencyHopTableList().getFirst().getHopTableID().intValue();
        }
    }

    private Integer selectPowerIndex(List<TransmitPowerLevelTableEntry> table) {
        if (table.isEmpty()) {
            logger.warn("Reader {} did not advertise a transmit power table. Leaving power at the reader's default.", readerID);
            return null;
        }

        String configured = readerConfig.optString("Power Level", "");
        double target = targetDBm(configured);

        List<TransmitPowerLevelTableEntry> sorted = new ArrayList<>(table);
        sorted.sort(Comparator.comparingInt(e -> e.getTransmitPowerValue().intValue()));

        TransmitPowerLevelTableEntry chosen = null;
        for (TransmitPowerLevelTableEntry e : sorted) {
            if (e.getTransmitPowerValue().intValue() / 100.0 <= target + 0.0001) {
                chosen = e;
            }
        }
        if (chosen == null) {
            chosen = sorted.getFirst();
            logger.warn("Reader {}: power level '{}' is below the reader's minimum. Using the minimum.", readerID, configured);
        }

        logger.info("Reader {} power level '{}' -> {} dBm (table index {}, reader range {} to {} dBm)",
                readerID, configured.isBlank() ? ImpinjPowerLevels.HIGH.getLevel() : configured,
                chosen.getTransmitPowerValue().intValue() / 100.0, chosen.getIndex(),
                sorted.getFirst().getTransmitPowerValue().intValue() / 100.0,
                sorted.getLast().getTransmitPowerValue().intValue() / 100.0);
        return chosen.getIndex().intValue();
    }

    /**
     * "Power Level" is either one of the named levels shared with the Impinj
     * readers (Low, Medium, High, Max) or a number in dBm.
     */
    private double targetDBm(String configured) {
        if (configured == null || configured.isBlank()) {
            return ImpinjPowerLevels.HIGH.getDBm();
        }
        String s = configured.trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            // not a number, try the names
        }
        for (ImpinjPowerLevels level : ImpinjPowerLevels.values()) {
            if (level.name().equalsIgnoreCase(s) || level.getLevel().equalsIgnoreCase(s)) {
                return level == ImpinjPowerLevels.MAX ? Double.POSITIVE_INFINITY : level.getDBm();
            }
        }
        logger.warn("Unknown Power Level '{}' for reader {}. Using {} ({} dBm).", configured, readerID,
                ImpinjPowerLevels.HIGH.getLevel(), ImpinjPowerLevels.HIGH.getDBm());
        return ImpinjPowerLevels.HIGH.getDBm();
    }

    // The smallest value is the most sensitive (LLRP values are dB relative to max sensitivity)
    private Integer selectRxSensitivityIndex(List<ReceiveSensitivityTableEntry> table) {
        return table.stream()
                .min(Comparator.comparingInt(e -> e.getReceiveSensitivityValue().intValue()))
                .map(e -> {
                    logger.debug("Reader {} receive sensitivity table index {} (value {})", readerID, e.getIndex(), e.getReceiveSensitivityValue());
                    return e.getIndex().intValue();
                })
                .orElse(null);
    }

    private void configureReader() throws ZebraException {
        // Keepalives let us notice a dead link, since the LTK has no "closed" callback
        SET_READER_CONFIG keepalive = newReaderConfig();
        KeepaliveSpec keepaliveSpec = new KeepaliveSpec();
        keepaliveSpec.setKeepaliveTriggerType(new KeepaliveTriggerType(KeepaliveTriggerType.Periodic));
        keepaliveSpec.setPeriodicTriggerValue(new UnsignedInteger(KEEPALIVE_MS));
        keepalive.setKeepaliveSpec(keepaliveSpec);
        trySetReaderConfig(keepalive, "keepalive");

        SET_READER_CONFIG events = newReaderConfig();
        EventsAndReports eventsAndReports = new EventsAndReports();
        eventsAndReports.setHoldEventsAndReportsUponReconnect(new Bit(0));
        events.setEventsAndReports(eventsAndReports);
        trySetReaderConfig(events, null);

        int[] notifications = {
            NotificationEventType.ROSpec_Event,
            NotificationEventType.AISpec_Event,
            NotificationEventType.Antenna_Event,
            NotificationEventType.Reader_Exception_Event,
            NotificationEventType.Report_Buffer_Fill_Warning
        };
        rospecEventsSupported = true;
        if (!trySetReaderConfig(eventNotificationConfig(notifications), null)) {
            logger.warn("Reader {} rejected the combined event notification config. Enabling events one at a time.", readerID);
            for (int n : notifications) {
                if (!trySetReaderConfig(eventNotificationConfig(n), new NotificationEventType(n) + " event")) {
                    if (n == NotificationEventType.ROSpec_Event) {
                        rospecEventsSupported = false;
                    }
                }
            }
        }

        activeSearchMode = searchMode;
        if (activeSearchMode != SearchMode.READER_DEFAULT && !stateAwareSupported) {
            logger.error("Reader {} does not support state-aware singulation, which {} needs. Falling back to ReaderDefault.", readerID, activeSearchMode);
            activeSearchMode = SearchMode.READER_DEFAULT;
        }
        if (activeSearchMode == SearchMode.DUAL_TARGET && maxSpecsPerROSpec == 1) {
            logger.error("Reader {} only allows one spec per ROSpec, and DualTarget needs two. Falling back to SingleTarget.", readerID);
            activeSearchMode = SearchMode.SINGLE_TARGET;
        }
        if (activeSearchMode == SearchMode.DUAL_TARGET && !rospecEventsSupported) {
            logger.error("Reader {} does not report ROSpec events, which DualTarget needs. Falling back to SingleTarget.", readerID);
            activeSearchMode = SearchMode.SINGLE_TARGET;
        }
    }

    private static SET_READER_CONFIG newReaderConfig() {
        SET_READER_CONFIG config = new SET_READER_CONFIG();
        config.setResetToFactoryDefault(new Bit(0));
        return config;
    }

    private static SET_READER_CONFIG eventNotificationConfig(int... eventTypes) {
        SET_READER_CONFIG config = newReaderConfig();
        ReaderEventNotificationSpec spec = new ReaderEventNotificationSpec();
        List<EventNotificationState> states = new ArrayList<>();
        for (int type : eventTypes) {
            EventNotificationState state = new EventNotificationState();
            state.setEventType(new NotificationEventType(type));
            state.setNotificationState(new Bit(1));
            states.add(state);
        }
        spec.setEventNotificationStateList(states);
        config.setReaderEventNotificationSpec(spec);
        return config;
    }

    /**
     * @param what description used in the warning if the reader rejects it, or
     * null to stay quiet
     * @return true if the reader accepted it
     */
    private boolean trySetReaderConfig(SET_READER_CONFIG config, String what) throws ZebraException {
        try {
            transact(config, SET_READER_CONFIG_RESPONSE.class);
            return true;
        } catch (ZebraException ex) {
            if (!ex.rejected) {
                throw ex;
            }
            if (what != null) {
                logger.warn("Reader {} rejected the {} config: {}", readerID, what, ex.getMessage());
            } else {
                logger.debug("Reader {} rejected a config update: {}", readerID, ex.getMessage());
            }
            return false;
        }
    }

    private void readReaderConfig() throws ZebraException {
        GET_READER_CONFIG request = new GET_READER_CONFIG();
        request.setAntennaID(new UnsignedShort(0));
        request.setRequestedData(new GetReaderConfigRequestedData(GetReaderConfigRequestedData.All));
        request.setGPIPortNum(new UnsignedShort(0));
        request.setGPOPortNum(new UnsignedShort(0));
        GET_READER_CONFIG_RESPONSE config = transact(request, GET_READER_CONFIG_RESPONSE.class);

        if (config.getIdentification() != null) {
            logger.info("Zebra reader {} LLRP reader ID: {}", readerID, config.getIdentification().getReaderID());
        }

        // Keep the reader's current regional hop table / channel per antenna.
        // We only change the transmit power.
        hopChannel = new HashMap<>();
        if (config.getAntennaConfigurationList() != null) {
            for (AntennaConfiguration ac : config.getAntennaConfigurationList()) {
                RFTransmitter tx = ac.getRFTransmitter();
                if (ac.getAntennaID() != null && tx != null && tx.getHopTableID() != null && tx.getChannelIndex() != null) {
                    hopChannel.put(ac.getAntennaID().intValue(), new int[]{tx.getHopTableID().intValue(), tx.getChannelIndex().intValue()});
                }
            }
        }
        if (hopChannel.containsKey(0)) {
            defaultHopChannel = hopChannel.get(0);
        }

        Set<Integer> ports = new TreeSet<>();
        for (int i = 1; i <= maxAntennas; i++) {
            ports.add(i);
        }
        Map<Integer, Boolean> antennaConnected = new HashMap<>();
        if (config.getAntennaPropertiesList() != null) {
            for (AntennaProperties ap : config.getAntennaPropertiesList()) {
                int id = ap.getAntennaID().intValue();
                if (id > 0) {
                    ports.add(id);
                    antennaConnected.put(id, ap.getAntennaConnected() != null && ap.getAntennaConnected().toBoolean());
                }
            }
        }

        disabledAntennas.stream().filter(p -> !ports.contains(p)).forEach(p
                -> logger.warn("Reader {}: Antenna Config disables port {}, but the reader only has ports {}", readerID, p, ports));

        antennaStatus.keySet().retainAll(ports);
        List<Integer> enabled = new ArrayList<>();
        for (Integer port : ports) {
            boolean isEnabled = !disabledAntennas.contains(port);
            boolean isConnected = antennaConnected.getOrDefault(port, false);
            logger.info(" Antenna Status: Reader: {} Port: {} Enabled: {} Connected: {} ", readerID, port, isEnabled, isConnected);
            antennaStatus.put(port, isEnabled ? isConnected ? "Connected" : "Disconnected" : "Disabled");
            if (isEnabled) {
                enabled.add(port);
            }
        }
        enabledAntennas = List.copyOf(enabled);

        if (enabledAntennas.isEmpty()) {
            throw new ZebraException("no enabled antenna ports (reader ports: " + ports + ", disabled: " + disabledAntennas + ")", false);
        }
    }

    private void addROSpec() throws ZebraException {
        List<SearchMode> attempts = activeSearchMode == SearchMode.READER_DEFAULT
                ? List.of(SearchMode.READER_DEFAULT)
                : List.of(activeSearchMode, SearchMode.READER_DEFAULT);

        ZebraException lastError = null;
        for (SearchMode mode : attempts) {
            ADD_ROSPEC add = new ADD_ROSPEC();
            add.setROSpec(buildROSpec(mode));
            try {
                transact(add, ADD_ROSPEC_RESPONSE.class);
                activeSearchMode = mode;
                lastError = null;
                break;
            } catch (ZebraException ex) {
                if (!ex.rejected) {
                    throw ex;
                }
                lastError = ex;
                logger.error("Reader {} rejected the {} ROSpec: {}", readerID, mode, ex.getMessage());
            }
        }
        if (lastError != null) {
            throw lastError;
        }

        ENABLE_ROSPEC enable = new ENABLE_ROSPEC();
        enable.setROSpecID(new UnsignedInteger(ROSPEC_ID));
        transact(enable, ENABLE_ROSPEC_RESPONSE.class);

        logger.info("Reader {} inventory: {}{}, session {}, tag population {}, RF mode {}, power index {}, antennas {}",
                readerID, activeSearchMode,
                activeSearchMode == SearchMode.DUAL_TARGET ? " (" + dwellMs + " ms per target)" : "",
                session, tagPopulation,
                modeIdentifier == null ? "reader default" : modeIdentifier,
                powerIndex == null ? "reader default" : powerIndex,
                enabledAntennas);
    }

    /**
     * Build the ROSpec. It has a Null start trigger (we start it with
     * START_ROSPEC) and a Null stop trigger (it runs until STOP_ROSPEC, or
     * until its spec list is done).
     *
     * LLRP 1.0.1 ROSpecs do not loop. In DualTarget mode the two AISpecs each
     * stop after Dwell ms, the ROSpec ends, and onROSpecEnded() restarts it.
     */
    ROSpec buildROSpec(SearchMode mode) {
        ROSpec rospec = new ROSpec();
        rospec.setROSpecID(new UnsignedInteger(ROSPEC_ID));
        rospec.setPriority(new UnsignedByte(0));
        rospec.setCurrentState(new ROSpecState(ROSpecState.Disabled));

        ROBoundarySpec boundary = new ROBoundarySpec();
        ROSpecStartTrigger start = new ROSpecStartTrigger();
        start.setROSpecStartTriggerType(new ROSpecStartTriggerType(ROSpecStartTriggerType.Null));
        boundary.setROSpecStartTrigger(start);
        ROSpecStopTrigger stop = new ROSpecStopTrigger();
        stop.setROSpecStopTriggerType(new ROSpecStopTriggerType(ROSpecStopTriggerType.Null));
        stop.setDurationTriggerValue(new UnsignedInteger(0));
        boundary.setROSpecStopTrigger(stop);
        rospec.setROBoundarySpec(boundary);

        switch (mode) {
            case DUAL_TARGET -> {
                rospec.addToSpecParameterList(buildAISpec(1, C1G2TagInventoryStateAwareI.State_A, dwellMs));
                rospec.addToSpecParameterList(buildAISpec(2, C1G2TagInventoryStateAwareI.State_B, dwellMs));
            }
            case SINGLE_TARGET ->
                rospec.addToSpecParameterList(buildAISpec(1, C1G2TagInventoryStateAwareI.State_A, 0));
            case READER_DEFAULT ->
                rospec.addToSpecParameterList(buildAISpec(1, null, 0));
        }

        // Report every tag read immediately so the TagReadProcessor can pick
        // the strongest read, just like the Impinj "Individual" report mode.
        ROReportSpec report = new ROReportSpec();
        report.setROReportTrigger(new ROReportTriggerType(ROReportTriggerType.Upon_N_Tags_Or_End_Of_AISpec));
        report.setN(new UnsignedShort(1));
        TagReportContentSelector selector = new TagReportContentSelector();
        selector.setEnableROSpecID(new Bit(0));
        selector.setEnableSpecIndex(new Bit(0));
        selector.setEnableInventoryParameterSpecID(new Bit(0));
        selector.setEnableAntennaID(new Bit(1));
        selector.setEnableChannelIndex(new Bit(0));
        selector.setEnablePeakRSSI(new Bit(1));
        selector.setEnableFirstSeenTimestamp(new Bit(1));
        selector.setEnableLastSeenTimestamp(new Bit(1));
        selector.setEnableTagSeenCount(new Bit(0));
        selector.setEnableAccessSpecID(new Bit(0));
        C1G2EPCMemorySelector memory = new C1G2EPCMemorySelector();
        memory.setEnableCRC(new Bit(0));
        memory.setEnablePCBits(new Bit(0));
        selector.addToAirProtocolEPCMemorySelectorList(memory);
        report.setTagReportContentSelector(selector);
        rospec.setROReportSpec(report);

        return rospec;
    }

    /**
     * @param target C1G2TagInventoryStateAwareI.State_A / State_B, or null for
     * a state-unaware inventory where the reader picks the target
     * @param durationMs AISpec duration, or 0 to run until the ROSpec stops
     */
    private AISpec buildAISpec(int specID, Integer target, int durationMs) {
        AISpec aispec = new AISpec();

        UnsignedShortArray antennaIDs = new UnsignedShortArray();
        enabledAntennas.forEach(a -> antennaIDs.add(new UnsignedShort(a)));
        aispec.setAntennaIDs(antennaIDs);

        AISpecStopTrigger stopTrigger = new AISpecStopTrigger();
        if (durationMs > 0) {
            stopTrigger.setAISpecStopTriggerType(new AISpecStopTriggerType(AISpecStopTriggerType.Duration));
            stopTrigger.setDurationTrigger(new UnsignedInteger(durationMs));
        } else {
            stopTrigger.setAISpecStopTriggerType(new AISpecStopTriggerType(AISpecStopTriggerType.Null));
            stopTrigger.setDurationTrigger(new UnsignedInteger(0));
        }
        aispec.setAISpecStopTrigger(stopTrigger);

        InventoryParameterSpec inventory = new InventoryParameterSpec();
        inventory.setInventoryParameterSpecID(new UnsignedShort(specID));
        inventory.setProtocolID(new AirProtocols(AirProtocols.EPCGlobalClass1Gen2));

        for (Integer port : enabledAntennas) {
            AntennaConfiguration antenna = new AntennaConfiguration();
            antenna.setAntennaID(new UnsignedShort(port));

            if (powerIndex != null) {
                int[] hc = hopChannel.getOrDefault(port, defaultHopChannel);
                RFTransmitter tx = new RFTransmitter();
                tx.setHopTableID(new UnsignedShort(hc[0]));
                tx.setChannelIndex(new UnsignedShort(hc[1]));
                tx.setTransmitPower(new UnsignedShort(powerIndex));
                antenna.setRFTransmitter(tx);
            }

            if (rxSensitivityIndex != null) {
                RFReceiver rx = new RFReceiver();
                rx.setReceiverSensitivity(new UnsignedShort(rxSensitivityIndex));
                antenna.setRFReceiver(rx);
            }

            C1G2InventoryCommand command = new C1G2InventoryCommand();
            command.setTagInventoryStateAware(new Bit(target != null));

            if (modeIdentifier != null) {
                C1G2RFControl rf = new C1G2RFControl();
                rf.setModeIndex(new UnsignedShort(modeIdentifier));
                rf.setTari(new UnsignedShort(0)); // 0 = let the reader pick within the mode's range
                command.setC1G2RFControl(rf);
            }

            C1G2SingulationControl singulation = new C1G2SingulationControl();
            singulation.setSession(new TwoBitField(Integer.toString(session)));
            singulation.setTagPopulation(new UnsignedShort(tagPopulation));
            singulation.setTagTransitTime(new UnsignedInteger(0));
            if (target != null) {
                C1G2TagInventoryStateAwareSingulationAction action = new C1G2TagInventoryStateAwareSingulationAction();
                action.setI(new C1G2TagInventoryStateAwareI(target));
                // Sel = ~SL, which matches every tag in its power-up state
                action.setS(new C1G2TagInventoryStateAwareS(C1G2TagInventoryStateAwareS.Not_SL));
                singulation.setC1G2TagInventoryStateAwareSingulationAction(action);
            }
            command.setC1G2SingulationControl(singulation);

            antenna.addToAirProtocolInventoryCommandSettingsList(command);
            inventory.addToAntennaConfigurationList(antenna);
        }

        aispec.addToInventoryParameterSpecList(inventory);
        return aispec;
    }

    /*
     * Reading
     */
    @Override
    public void startReading() {
        logger.info("Starting the reader with the following config: " + readerConfig.toString(4));
        wantReading = true;
        startInventory();
    }

    /**
     * Start reading again after a reconnect or an unexpected stop, without the
     * user-facing logging.
     */
    void resumeReading() {
        if (wantReading) {
            startInventory();
        }
    }

    private void startInventory() {
        startStopLock.lock();
        try {
            lastStartAttemptNanos = System.nanoTime();
            if (!connected && !connect()) {
                logger.warn("Reader {} ({}) is not connected. It will start reading as soon as it connects.", readerID, readerIP);
                return;
            }
            if (reading || !wantReading) {
                return;
            }

            START_ROSPEC start = new START_ROSPEC();
            start.setROSpecID(new UnsignedInteger(ROSPEC_ID));
            synchronized (rospecLock) {
                quickROSpecEnds = 0;
                lastStartNanos = System.nanoTime();
            }
            transact(start, START_ROSPEC_RESPONSE.class);
            reading = true;
            logger.info("Reader {} ({}) is reading", readerID, readerIP);
        } catch (ZebraException ex) {
            logger.error("Unable to start reader {}: {}", readerID, ex.getMessage());
        } finally {
            startStopLock.unlock();
        }
    }

    @Override
    public void stopReading() {
        synchronized (rospecLock) {
            // Flip this first so the DualTarget loop stops restarting the ROSpec
            wantReading = false;
        }

        startStopLock.lock();
        try {
            if (connected) {
                // Always send the stop, even if we think we're idle. It's harmless if it was already stopped.
                STOP_ROSPEC stop = new STOP_ROSPEC();
                stop.setROSpecID(new UnsignedInteger(ROSPEC_ID));
                try {
                    transact(stop, STOP_ROSPEC_RESPONSE.class);
                } catch (ZebraException ex) {
                    if (ex.rejected) {
                        logger.debug("Reader {} STOP_ROSPEC: {}", readerID, ex.getMessage());
                    } else {
                        logger.warn("Reader {} did not acknowledge the stop: {}", readerID, ex.getMessage());
                    }
                }
            }
            if (reading) {
                logger.info("Reader {} ({}) stopped", readerID, readerIP);
            }
            reading = false;
        } finally {
            startStopLock.unlock();
        }
    }

    /*
     * Callbacks from the ZebraMessageHandler. These run on the LLRP IO thread,
     * so they must not block or call transact().
     */
    void onROSpecStarted(ZebraMessageHandler from) {
        if (from != handler) {
            return;
        }
        logger.trace("Reader {} ROSpec started", readerID);
        if (wantReading) {
            reading = true;
        }
    }

    void onROSpecEnded(ZebraMessageHandler from, boolean preempted) {
        if (from != handler) {
            return;
        }
        synchronized (rospecLock) {
            if (!wantReading || !connected) {
                logger.trace("Reader {} ROSpec ended", readerID);
                return;
            }
            LLRPConnector c = connection;
            if (c == null) {
                return;
            }

            long now = System.nanoTime();
            long ranMs = (now - lastStartNanos) / 1_000_000L;
            long healthyMs = activeSearchMode == SearchMode.DUAL_TARGET ? dwellMs : 1000;
            quickROSpecEnds = ranMs < healthyMs / 2 ? quickROSpecEnds + 1 : 0;
            if (quickROSpecEnds >= MAX_QUICK_ROSPEC_ENDS) {
                logger.error("Reader {} keeps ending its ROSpec after only {} ms. Not restarting it; will retry in a few seconds.", readerID, ranMs);
                reading = false;
                return;
            }

            if (preempted || activeSearchMode != SearchMode.DUAL_TARGET) {
                logger.warn("Reader {} ROSpec {} unexpectedly. Restarting it.", readerID, preempted ? "was preempted" : "ended");
            } else {
                logger.trace("Reader {} dual target cycle complete, restarting the ROSpec", readerID);
            }

            START_ROSPEC start = new START_ROSPEC();
            start.setROSpecID(new UnsignedInteger(ROSPEC_ID));
            lastStartNanos = now;
            // send() does not wait for the response; it comes back through the
            // message handler, which logs it if the reader refused.
            c.send(start);
        }
    }

    void onROSpecRestartFailed(ZebraMessageHandler from) {
        if (from != handler) {
            return;
        }
        if (wantReading) {
            logger.error("Reader {} stopped reading because its ROSpec could not be restarted. Will retry in a few seconds.", readerID);
        }
        reading = false;
    }

    void onLinkError(ZebraMessageHandler from, String error) {
        if (error == null) {
            return;
        }
        String e = error.toLowerCase();
        boolean fatal = e.contains("ioexception") || e.contains("closed") || e.contains("reset")
                || e.contains("not yet connected") || e.contains("not yet established");
        if (fatal && from == handler && connected) {
            logger.warn("Reader {} link error: {}", readerID, error);
            linkError = error;
        } else {
            logger.warn("Reader {} LLRP error: {}", readerID, error);
        }
    }

    /*
     * Link monitoring. Called by the ZebraConnectionMonitor once a second while connected.
     */
    void checkLink() {
        String error = linkError;
        if (error != null) {
            connectionLost(error);
            return;
        }

        ZebraMessageHandler h = handler;
        if (h != null && h.millisSinceLastMessage() > LINK_TIMEOUT_MS) {
            long silentFor = h.millisSinceLastMessage() / 1000;
            if (probe()) {
                if (!warnedNoKeepalives) {
                    logger.warn("Reader {} is not sending LLRP keepalives. Polling it instead.", readerID);
                    warnedNoKeepalives = true;
                }
                h.touch();
            } else {
                connectionLost("no keepalive or response from the reader for " + silentFor + " seconds");
                return;
            }
        }

        if (wantReading && !reading && System.nanoTime() - lastStartAttemptNanos > START_RETRY_NANOS) {
            logger.warn("Reader {} should be reading but isn't. Restarting it.", readerID);
            resumeReading();
        }
    }

    /**
     * Ask the reader something cheap to see if the link is alive.
     */
    private boolean probe() {
        if (!commandLock.tryLock()) {
            // A command is in flight. Its outcome will tell us if the link is dead.
            return true;
        }
        try {
            GET_READER_CONFIG request = new GET_READER_CONFIG();
            request.setAntennaID(new UnsignedShort(0));
            request.setRequestedData(new GetReaderConfigRequestedData(GetReaderConfigRequestedData.KeepaliveSpec));
            request.setGPIPortNum(new UnsignedShort(0));
            request.setGPOPortNum(new UnsignedShort(0));
            transact(request, GET_READER_CONFIG_RESPONSE.class, PROBE_TIMEOUT_MS);
            return true;
        } catch (ZebraException ex) {
            return ex.rejected;
        } finally {
            commandLock.unlock();
        }
    }

    private void connectionLost(String reason) {
        logger.error("Connection to Zebra reader {} ({}) lost: {}", readerID, readerIP, reason);
        // wantReading is left alone so the monitor puts the reader back into
        // read mode once it reconnects
        teardown();
    }

    private void teardown() {
        connected = false;
        reading = false;
        LLRPConnector c = connection;
        connection = null;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ex) {
                logger.debug("Reader {} disconnect error", readerID, ex);
            }
        }
    }

    /*
     * Clock
     */
    @Override
    public void setClock() {
        // LLRP has no way to set a Zebra reader's clock. In Delta mode we
        // measure the offset to the Pi's clock instead. In NTP mode we trust
        // (and keep checking) the reader's own NTP-synced clock.
        logger.info("Reader {} ({}) clock mode {}: {}", readerID, readerIP,
                clock.getMode() == ReaderClock.Mode.READER ? "NTP" : "Delta", clock.describe());
    }

    /*
     * Antennas
     */
    @Override
    public Map<Integer, String> getAntennaStatus() {
        long now = Instant.now().getEpochSecond();

        // Antenna events only fire reliably while reading, so poll the
        // antenna properties every few seconds while idle. Do it off the
        // caller's thread so the status update never waits on the reader.
        if (connected && !reading && now - lastAntennaPollSec > ANTENNA_POLL_SECONDS) {
            lastAntennaPollSec = now;
            Thread.startVirtualThread(this::pollAntennaStatus);
        }
        return antennaStatus;
    }

    private void pollAntennaStatus() {
        if (!commandLock.tryLock()) {
            return;
        }
        try {
            GET_READER_CONFIG request = new GET_READER_CONFIG();
            request.setAntennaID(new UnsignedShort(0));
            request.setRequestedData(new GetReaderConfigRequestedData(GetReaderConfigRequestedData.AntennaProperties));
            request.setGPIPortNum(new UnsignedShort(0));
            request.setGPOPortNum(new UnsignedShort(0));
            GET_READER_CONFIG_RESPONSE response = transact(request, GET_READER_CONFIG_RESPONSE.class);
            if (response.getAntennaPropertiesList() != null) {
                response.getAntennaPropertiesList().forEach(ap -> {
                    if (ap.getAntennaID().intValue() > 0) {
                        updateAntennaStatus(ap.getAntennaID().intValue(), ap.getAntennaConnected() != null && ap.getAntennaConnected().toBoolean());
                    }
                });
            }
        } catch (ZebraException ex) {
            logger.debug("Reader {} antenna status poll failed: {}", readerID, ex.getMessage());
        } finally {
            commandLock.unlock();
        }
    }

    void updateAntennaStatus(int port, boolean isConnected) {
        if (disabledAntennas.contains(port)) {
            antennaStatus.put(port, "Disabled");
            return;
        }
        String status = isConnected ? "Connected" : "Disconnected";
        String old = antennaStatus.put(port, status);
        if (old != null && !old.equals(status)) {
            logger.info("Antenna Status Change: Reader: {} Port: {} State: {}", readerID, port, status);
        }
    }

    /**
     * Make sure every port we report a read on has a status entry, since the
     * StatusHandler looks them up by port.
     */
    void noteAntennaSeen(int port) {
        antennaStatus.putIfAbsent(port, disabledAntennas.contains(port) ? "Disabled" : "Connected");
    }

    /*
     * LLRP plumbing
     */
    private <T extends LLRPMessage> T transact(LLRPMessage request, Class<T> responseType) throws ZebraException {
        return transact(request, responseType, TRANSACT_TIMEOUT_MS);
    }

    /**
     * Send a command and wait for its response. LLRPConnection.transact() is
     * not thread safe (it tracks one outstanding request per session), so all
     * commands for this reader go through the command lock.
     */
    private <T extends LLRPMessage> T transact(LLRPMessage request, Class<T> responseType, long timeoutMs) throws ZebraException {
        LLRPConnector c = connection;
        if (c == null) {
            throw new ZebraException(request.getName() + ": not connected", false);
        }
        commandLock.lock();
        try {
            // A response that arrived after an earlier timeout would otherwise
            // be handed to this request
            c.getHandler().getSynMessageQueue().clear();

            LLRPMessage response = c.transact(request, timeoutMs);
            if (response == null) {
                throw new ZebraException(request.getName() + ": no response (connection down)", false);
            }
            if (!responseType.isInstance(response)) {
                throw new ZebraException(request.getName() + ": unexpected response " + response.getName(), false);
            }
            LLRPStatus status = statusOf(response);
            if (status != null && status.getStatusCode() != null && status.getStatusCode().intValue() != StatusCode.M_Success) {
                throw new ZebraException(request.getName() + " failed: " + describe(status), true);
            }
            return responseType.cast(response);
        } catch (TimeoutException ex) {
            throw new ZebraException(request.getName() + ": timed out after " + timeoutMs + " ms", false);
        } finally {
            commandLock.unlock();
        }
    }

    private void transactIgnoringRejection(LLRPMessage request, Class<? extends LLRPMessage> responseType) throws ZebraException {
        try {
            transact(request, responseType);
        } catch (ZebraException ex) {
            if (!ex.rejected) {
                throw ex;
            }
            logger.debug("Reader {}: {}", readerID, ex.getMessage());
        }
    }

    // Every LLRP response has a getLLRPStatus(), but there is no common interface for it
    private static LLRPStatus statusOf(LLRPMessage message) {
        try {
            Method getter = message.getClass().getMethod("getLLRPStatus");
            return (LLRPStatus) getter.invoke(message);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            return null;
        }
    }

    static String describe(LLRPStatus status) {
        if (status == null) {
            return "no status";
        }
        StringBuilder sb = new StringBuilder(String.valueOf(status.getStatusCode()));
        if (status.getErrorDescription() != null && !status.getErrorDescription().toString().isBlank()) {
            sb.append(": ").append(status.getErrorDescription());
        }
        if (status.getFieldError() != null) {
            sb.append(" [field ").append(status.getFieldError().getFieldNum())
                    .append(": ").append(status.getFieldError().getErrorCode()).append("]");
        }
        ParameterError pe = status.getParameterError();
        while (pe != null) {
            sb.append(" [parameter type ").append(pe.getParameterType()).append(": ").append(pe.getErrorCode());
            if (pe.getFieldError() != null) {
                sb.append(", field ").append(pe.getFieldError().getFieldNum()).append(": ").append(pe.getFieldError().getErrorCode());
            }
            sb.append("]");
            pe = pe.getParameterError();
        }
        return sb.toString();
    }
}
