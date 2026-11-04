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

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.llrp.ltk.generated.enumerations.C1G2MValue;
import org.llrp.ltk.generated.enumerations.C1G2SpectralMaskIndicator;
import org.llrp.ltk.generated.parameters.C1G2UHFRFModeTableEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named RF mode profiles for Zebra FX readers.
 *
 * Zebra's C1G2 RF mode table (the "link profiles") is numbered differently on
 * each model, region and firmware, so Impinj mode numbers (e.g. 4 = MaxMiller,
 * 5 = DenseReaderM4Two) do not carry over. Instead we match a named profile
 * against the table the reader advertises in GET_READER_CAPABILITIES using the
 * Miller value, backscatter link frequency and spectral mask. A raw
 * ModeIdentifier from the reader's table may also be given in the config.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public enum ZebraReaderMode {

    // Miller-4, BLF near 250 kHz, dense-interrogator mask preferred. The default.
    DENSE_READER_M4("DenseReaderM4", 4, 250_000L, true),
    // Alias for DenseReaderM4 so configs written for Impinj mode 5 work
    DENSE_READER_M4_TWO("DenseReaderM4Two", 4, 250_000L, true),
    // Miller-4 at the highest BLF the reader offers
    MAX_MILLER("MaxMiller", 4, null, false),
    // Miller-8, dense-interrogator mask preferred
    DENSE_READER_M8("DenseReaderM8", 8, 250_000L, true),
    // Miller-2
    HYBRID("Hybrid", 2, 250_000L, false),
    // FM0 at the highest BLF. Fast in a quiet room, poor next to other readers.
    MAX_THROUGHPUT("MaxThroughput", 1, null, false);

    public static final ZebraReaderMode DEFAULT = DENSE_READER_M4;

    private static final Logger logger = LoggerFactory.getLogger(ZebraReaderMode.class);
    private static final long MAX_GEN2_BLF = 640_000L;

    private final String label;
    private final int millerFactor;
    private final Long targetBLF; // null = highest available
    private final boolean preferDenseMask;

    ZebraReaderMode(String label, int millerFactor, Long targetBLF, boolean preferDenseMask) {
        this.label = label;
        this.millerFactor = millerFactor;
        this.targetBLF = targetBLF;
        this.preferDenseMask = preferDenseMask;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    public static ZebraReaderMode fromName(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().replace(" ", "").replace("_", "");
        for (ZebraReaderMode m : values()) {
            if (m.label.equalsIgnoreCase(n) || m.name().replace("_", "").equalsIgnoreCase(n)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Pick a mode table entry for the configured "Reader Mode" value.
     *
     * @return the chosen entry, or null if the reader advertised no modes
     */
    public static C1G2UHFRFModeTableEntry select(String configValue, List<C1G2UHFRFModeTableEntry> table, Integer readerID) {
        if (table == null || table.isEmpty()) {
            logger.warn("Reader {} did not advertise an RF mode table. Leaving the RF mode at the reader's default.", readerID);
            return null;
        }

        ZebraReaderMode profile = DEFAULT;
        if (configValue != null && !configValue.isBlank()) {
            String v = configValue.trim();
            if (v.matches("\\d+")) {
                long id = Long.parseLong(v);
                Optional<C1G2UHFRFModeTableEntry> raw = table.stream()
                        .filter(e -> e.getModeIdentifier().toLong() == id).findFirst();
                if (raw.isPresent()) {
                    logger.info("Reader {} using raw RF mode {} from config: {}", readerID, id, describe(raw.get()));
                    return raw.get();
                }
                logger.warn("Reader {} has no RF mode {} (see the mode table above). Falling back to {}.", readerID, id, DEFAULT);
            } else {
                ZebraReaderMode named = fromName(v);
                if (named == null) {
                    logger.warn("Unknown Reader Mode '{}' for reader {}. Falling back to {}.", v, readerID, DEFAULT);
                } else {
                    profile = named;
                }
            }
        }

        if (profile == MAX_THROUGHPUT) {
            logger.warn("Reader {} is set to MaxThroughput (FM0). This is usually a poor choice when other readers are nearby.", readerID);
        }

        Optional<C1G2UHFRFModeTableEntry> match = profile.bestMatch(table);
        if (match.isEmpty() && profile != DEFAULT) {
            logger.warn("Reader {} has no RF mode matching {}. Trying {}.", readerID, profile, DEFAULT);
            profile = DEFAULT;
            match = profile.bestMatch(table);
        }
        if (match.isEmpty()) {
            C1G2UHFRFModeTableEntry first = table.getFirst();
            logger.warn("Reader {} has no Miller-4 RF mode. Using the first mode in its table: {}", readerID, describe(first));
            return first;
        }
        logger.info("Reader {} using RF mode profile {}: {}", readerID, profile, describe(match.get()));
        return match.get();
    }

    private Optional<C1G2UHFRFModeTableEntry> bestMatch(List<C1G2UHFRFModeTableEntry> table) {
        Comparator<C1G2UHFRFModeTableEntry> byMask = Comparator.comparingInt(ZebraReaderMode::maskRank);
        Comparator<C1G2UHFRFModeTableEntry> byBLF = targetBLF == null
                ? Comparator.comparingLong(ZebraReaderMode::blf).reversed()
                : Comparator.comparingLong(e -> Math.abs(blf(e) - targetBLF));
        Comparator<C1G2UHFRFModeTableEntry> order = preferDenseMask ? byMask.thenComparing(byBLF) : byBLF.thenComparing(byMask);
        order = order.thenComparingLong(e -> e.getModeIdentifier().toLong());

        return table.stream()
                .filter(e -> millerFactor(e) == millerFactor)
                .sorted(order)
                .findFirst();
    }

    static int millerFactor(C1G2UHFRFModeTableEntry e) {
        if (e.getMValue() == null) {
            return 0;
        }
        return switch (e.getMValue().intValue()) {
            case C1G2MValue.MV_FM0 ->
                1;
            case C1G2MValue.MV_2 ->
                2;
            case C1G2MValue.MV_4 ->
                4;
            case C1G2MValue.MV_8 ->
                8;
            default ->
                0;
        };
    }

    /**
     * Backscatter link frequency in Hz. LLRP's BDRValue is the backscatter
     * data rate (BLF / M), but some readers put the BLF itself in that field.
     * Gen2 caps BLF at 640 kHz, so anything above that must already be the BLF.
     */
    static long blf(C1G2UHFRFModeTableEntry e) {
        long bdr = e.getBDRValue() == null ? 0 : e.getBDRValue().toLong();
        long m = Math.max(1, millerFactor(e));
        long candidate = bdr * m;
        return candidate <= MAX_GEN2_BLF ? candidate : bdr;
    }

    // Dense interrogator first, then multi, then single, then unknown
    static int maskRank(C1G2UHFRFModeTableEntry e) {
        if (e.getSpectralMaskIndicator() == null) {
            return 3;
        }
        return switch (e.getSpectralMaskIndicator().intValue()) {
            case C1G2SpectralMaskIndicator.DI ->
                0;
            case C1G2SpectralMaskIndicator.MI ->
                1;
            case C1G2SpectralMaskIndicator.SI ->
                2;
            default ->
                3;
        };
    }

    static String describe(C1G2UHFRFModeTableEntry e) {
        int m = millerFactor(e);
        String mask = e.getSpectralMaskIndicator() == null ? "?" : e.getSpectralMaskIndicator().toString();
        return String.format("Mode %d: %s BLF %d kHz (BDR %s) mask %s PIE %.2f Tari %.2f-%.2f us",
                e.getModeIdentifier().toLong(),
                m == 1 ? "FM0" : "M" + m,
                blf(e) / 1000,
                e.getBDRValue(),
                mask,
                e.getPIEValue() == null ? 0.0 : e.getPIEValue().toLong() / 1000.0,
                e.getMinTariValue() == null ? 0.0 : e.getMinTariValue().toLong() / 1000.0,
                e.getMaxTariValue() == null ? 0.0 : e.getMaxTariValue().toLong() / 1000.0);
    }
}
