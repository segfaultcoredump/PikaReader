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

import java.time.Instant;
import java.util.ArrayDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the offset between a Zebra reader's internal clock and the Pi's clock.
 *
 * LLRP has no "what time is it" request. The only places the reader exposes its
 * clock are the timestamps it puts on READER_EVENT_NOTIFICATIONs and tag
 * reports. Each of those gives us a sample of (Pi receive time - reader time),
 * which is the true clock offset plus the (always positive) delivery latency.
 * The minimum sample over a sliding window is therefore a good estimate of the
 * true offset, and it tracks reader clock drift over the course of a race.
 *
 * In DELTA mode (the default) every tag timestamp is shifted onto the Pi's
 * clock. In NTP mode the reader's own UTC timestamps are trusted as-is, but the
 * offset is still tracked so we can warn if the reader's NTP isn't working.
 *
 * One instance per reader; every reader drifts differently.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ZebraClock {

    private static final Logger logger = LoggerFactory.getLogger(ZebraClock.class);

    public enum Mode {
        DELTA, NTP;

        public static Mode fromConfig(String s) {
            if (s != null && s.trim().equalsIgnoreCase("NTP")) {
                return NTP;
            }
            if (s != null && !s.isBlank() && !s.trim().equalsIgnoreCase("Delta")) {
                logger.warn("Unknown Zebra \"Clock\" setting '{}'. Using Delta (Pi time).", s);
            }
            return DELTA;
        }
    }

    /**
     * LLRP timestamps are either UTC (microseconds since the epoch) or Uptime
     * (microseconds since the reader booted). Each kind gets its own estimator.
     */
    public enum Kind {
        UTC, UPTIME
    }

    private static final long WINDOW_SECONDS = 60;
    private static final long NTP_WARN_MICROS = 1_000_000L;
    private static final long NTP_WARN_INTERVAL_MICROS = 60_000_000L;
    private static final long CHANGE_LOG_MICROS = 100_000L;

    private final Integer readerID;
    private final Mode mode;
    private final Estimator utc = new Estimator(Kind.UTC);
    private final Estimator uptime = new Estimator(Kind.UPTIME);

    private long lastNtpWarnMicros = Long.MIN_VALUE / 2;
    private boolean warnedUptimeInNtpMode = false;

    public ZebraClock(Integer readerID, Mode mode) {
        this.readerID = readerID;
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * The Pi's current time in microseconds since the epoch.
     */
    public static long nowMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }

    /**
     * Forget everything. Called on every (re)connect since the reader may have
     * rebooted or had its clock changed while we were away.
     */
    public synchronized void reset() {
        utc.reset();
        uptime.reset();
        warnedUptimeInNtpMode = false;
    }

    /**
     * Record a reader timestamp along with the Pi time it was received at.
     */
    public synchronized void sample(Kind kind, long readerMicros, long piReceiveMicros) {
        estimator(kind).add(piReceiveMicros - readerMicros, piReceiveMicros);
        if (kind == Kind.UTC && mode == Mode.NTP) {
            checkNtp(piReceiveMicros);
        }
    }

    /**
     * Convert a reader timestamp into Pi epoch microseconds.
     */
    public synchronized long toEpochMicros(Kind kind, long readerMicros, long piReceiveMicros) {
        if (kind == Kind.UTC && mode == Mode.NTP) {
            return readerMicros;
        }
        if (kind == Kind.UPTIME && mode == Mode.NTP && !warnedUptimeInNtpMode) {
            logger.warn("Reader {} reports uptime-based timestamps, so NTP clock mode is not possible. Using the Pi clock delta instead.", readerID);
            warnedUptimeInNtpMode = true;
        }
        Long offset = estimator(kind).estimate;
        if (offset == null) {
            // No reference sample yet. The best we can do is the receive time.
            return piReceiveMicros;
        }
        return readerMicros + offset;
    }

    /**
     * The current offset estimate (Pi - reader) in microseconds, or null if
     * nothing has been sampled yet. UTC takes precedence over Uptime.
     */
    public synchronized Long getOffsetMicros() {
        return utc.estimate != null ? utc.estimate : uptime.estimate;
    }

    public synchronized String describe() {
        if (utc.estimate != null) {
            return describeOffset(utc.estimate) + (mode == Mode.NTP ? " (reader time is used as-is)" : " (tag times are corrected to Pi time)");
        }
        if (uptime.estimate != null) {
            return "reader uses uptime timestamps; offset to Pi clock is " + String.format("%.3f", uptime.estimate / 1_000_000.0) + " s";
        }
        return "no clock samples received from the reader yet";
    }

    private static String describeOffset(long offsetMicros) {
        double ms = Math.abs(offsetMicros) / 1000.0;
        if (offsetMicros >= 0) {
            return String.format("reader clock is %.1f ms behind the Pi", ms);
        }
        return String.format("reader clock is %.1f ms ahead of the Pi", ms);
    }

    private Estimator estimator(Kind kind) {
        return kind == Kind.UTC ? utc : uptime;
    }

    private void checkNtp(long piMicros) {
        Long offset = utc.estimate;
        if (offset != null && Math.abs(offset) > NTP_WARN_MICROS && piMicros - lastNtpWarnMicros > NTP_WARN_INTERVAL_MICROS) {
            logger.warn("Reader {} is in NTP clock mode but its {}. Is NTP configured and working on the reader? "
                    + "Set \"Clock\": \"Delta\" to use the Pi's clock instead.", readerID, describeOffset(offset));
            lastNtpWarnMicros = piMicros;
        }
    }

    /**
     * Sliding-window minimum over per-second buckets.
     */
    private final class Estimator {

        private final Kind kind;
        // each entry is {epochSecond, minimum sample seen in that second}
        private final ArrayDeque<long[]> buckets = new ArrayDeque<>();
        private Long estimate = null;
        private Long lastLogged = null;

        Estimator(Kind kind) {
            this.kind = kind;
        }

        void reset() {
            buckets.clear();
            estimate = null;
            lastLogged = null;
        }

        void add(long sample, long piMicros) {
            long second = Math.floorDiv(piMicros, 1_000_000L);
            long[] last = buckets.peekLast();
            boolean pruned = false;

            if (last != null && second < last[0]) {
                // The Pi clock stepped backwards (NTP/GPS correction). Start over.
                logger.info("Pi clock stepped backwards; resetting the clock offset window for reader {}", readerID);
                buckets.clear();
                last = null;
                pruned = true;
            }

            if (last != null && last[0] == second) {
                if (sample < last[1]) {
                    last[1] = sample;
                }
            } else {
                buckets.addLast(new long[]{second, sample});
                while (!buckets.isEmpty() && buckets.peekFirst()[0] < second - WINDOW_SECONDS) {
                    buckets.removeFirst();
                    pruned = true;
                }
            }

            if (pruned || estimate == null) {
                long min = Long.MAX_VALUE;
                for (long[] b : buckets) {
                    min = Math.min(min, b[1]);
                }
                if (min != Long.MAX_VALUE) {
                    estimate = min;
                }
            } else if (sample < estimate) {
                estimate = sample;
            }

            if (lastLogged == null) {
                logger.info("Reader {} initial {} clock offset: {}", readerID, kind, describeOffset(estimate));
                lastLogged = estimate;
            } else if (Math.abs(estimate - lastLogged) > CHANGE_LOG_MICROS) {
                logger.info("Reader {} {} clock offset changed by {} ms: {}", readerID, kind,
                        String.format("%.1f", (estimate - lastLogged) / 1000.0), describeOffset(estimate));
                lastLogged = estimate;
            }
        }
    }
}
