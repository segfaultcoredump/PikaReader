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
package com.pikatimer.pikareader.tags;

import com.pikatimer.pikareader.readers.profiles.ReaderProfile;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the raw reads for a tag are boiled down to the reads sent to the
 * timing software. Each reader profile has its own filter.
 *
 * These are PikaReader's own settings, not reader RF settings, so they live
 * here and not in reader-profiles.json.
 *
 * @param type the filter
 * @param windowMs WINDOW: the length of each window
 * @param dropDb RSSI_PEAK: how far the averaged RSSI must fall below its
 * peak before the tag is reported
 * @param smoothingReads RSSI_PEAK: how many reads per antenna are averaged
 * @param dropHoldMs RSSI_PEAK: how long the averaged RSSI must stay dropDb
 * below its peak before the tag is reported
 * @param lostMs RSSI_PEAK: report a tag once it has not been seen for this
 * long, and ignore it after a report until it has not been seen for this long
 * @param maxHoldMs RSSI_PEAK: report a tag that is still in view this long
 * after its first read
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public record ReadFilterSettings(Type type, long windowMs, double dropDb, int smoothingReads, long dropHoldMs, long lostMs, long maxHoldMs) {

    public enum Type {
        /**
         * The strongest read in each fixed window. The window opens on a
         * tag's first read.
         */
        WINDOW,
        /**
         * Follow the RSSI while it rises and report the strongest read once
         * it has fallen off, or the tag is gone.
         */
        RSSI_PEAK
    }

    private static final Logger logger = LoggerFactory.getLogger(ReadFilterSettings.class);

    /**
     * "Reader" config key: the Finish Mats window, in seconds.
     */
    public static final String FINISH_MATS_GATING = "Finish Mats Gating";

    // Start Mats: a runner standing on the mats is reported once a second
    static final long START_MATS_WINDOW_MS = 1000;
    // Start Panels: a source of last resort that sees a lot of runners at
    // once, so report each tag less often
    static final long START_PANELS_WINDOW_MS = 3000;
    // Finish Mats: the mats point straight up and see a runner for 1-2 seconds
    static final long FINISH_MATS_WINDOW_MS = 2000;
    // Finish and split side panels: a runner can be seen from a long way
    // off, approaching at 1-5 m/s. A 6 dB drop is about 1.4 times the
    // distance of the closest approach. Single reads fade by a few dB
    // (multipath, frequency hopping), so the last few reads are averaged,
    // and the drop has to last: while a runner is still far away the RSSI
    // rises slowly, and noise alone can dip 6 dB below a noisy peak. After
    // the runner passes, the RSSI keeps falling.
    static final double PEAK_DROP_DB = 6.0;
    static final int PEAK_SMOOTHING_READS = 3;
    static final long PEAK_DROP_HOLD_MS = 1000;
    static final long PEAK_LOST_MS = 3000;
    // A tag that stays in view with a flat RSSI (a finisher standing in the
    // chute) never drops off, so report it after this long
    static final long PEAK_MAX_HOLD_MS = 15000;

    // Config problems already warned about, so a bad value is logged once
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    public static ReadFilterSettings window(long windowMs) {
        return new ReadFilterSettings(Type.WINDOW, windowMs, 0, 0, 0, 0, 0);
    }

    public static ReadFilterSettings rssiPeak(double dropDb, int smoothingReads, long dropHoldMs, long lostMs, long maxHoldMs) {
        return new ReadFilterSettings(Type.RSSI_PEAK, 0, dropDb, smoothingReads, dropHoldMs, lostMs, maxHoldMs);
    }

    /**
     * The read filter for a profile.
     *
     * @param profile the reader profile
     * @param readerConfig the "Reader" config, for the Finish Mats override
     */
    public static ReadFilterSettings forProfile(ReaderProfile profile, JSONObject readerConfig) {
        return switch (profile.id()) {
            case "start_mats" ->
                window(START_MATS_WINDOW_MS);
            case "start_side_panels" ->
                window(START_PANELS_WINDOW_MS);
            case "finish_mats" ->
                window(finishMatsWindow(readerConfig));
            case "finish_side_panels", "split_side_panels" ->
                rssiPeak(PEAK_DROP_DB, PEAK_SMOOTHING_READS, PEAK_DROP_HOLD_MS, PEAK_LOST_MS, PEAK_MAX_HOLD_MS);
            default -> {
                if (warned.add("profile:" + profile.id())) {
                    logger.warn("No read filter is defined for the {} profile. Using the strongest read every {}", profile, seconds(FINISH_MATS_WINDOW_MS));
                }
                yield window(FINISH_MATS_WINDOW_MS);
            }
        };
    }

    private static long finishMatsWindow(JSONObject readerConfig) {
        if (readerConfig == null || !readerConfig.has(FINISH_MATS_GATING)) {
            return FINISH_MATS_WINDOW_MS;
        }
        double s = readerConfig.optDouble(FINISH_MATS_GATING, Double.NaN);
        if (Double.isNaN(s) || Double.isInfinite(s) || s <= 0) {
            Object raw = readerConfig.opt(FINISH_MATS_GATING);
            if (warned.add("finish:" + raw)) {
                logger.warn("\"{}\" must be a number of seconds greater than 0, not {}. Using {}",
                        FINISH_MATS_GATING, JSONObject.valueToString(raw), seconds(FINISH_MATS_WINDOW_MS));
            }
            return FINISH_MATS_WINDOW_MS;
        }
        return Math.round(s * 1000);
    }

    /**
     * A one-line description, for the logs and the web UI.
     */
    public String summary() {
        return switch (type) {
            case WINDOW ->
                "Strongest read every " + seconds(windowMs);
            case RSSI_PEAK ->
                "Strongest read once the RSSI (" + smoothingReads + "-read average) has stayed "
                + BigDecimal.valueOf(dropDb).stripTrailingZeros().toPlainString() + " dB below its peak for "
                + seconds(dropHoldMs) + ", the tag is unseen for " + seconds(lostMs)
                + ", or " + seconds(maxHoldMs) + " after its first read";
        };
    }

    static String seconds(long ms) {
        return BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString() + " s";
    }
}
