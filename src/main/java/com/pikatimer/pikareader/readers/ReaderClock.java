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
package com.pikatimer.pikareader.readers;

import java.time.Instant;
import java.util.ArrayDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks the offset between an RFID reader's internal clock and the Pi's clock.
 *
 * The reader's clock shows up in the timestamps it puts on events and tag
 * reports. Each of those gives us a sample of (Pi receive time - reader time),
 * which is the true clock offset plus the (always positive) delivery latency.
 * The minimum sample over a sliding window is therefore a good estimate of the
 * true offset, and it tracks reader clock drift over the course of a race.
 *
 * In DELTA mode every tag timestamp is shifted onto the Pi's clock. In READER
 * mode the reader's own UTC timestamps are used as-is (because the reader is
 * on NTP, or its clock was set from the Pi), but the offset is still tracked
 * so we can warn if the reader's clock is wrong.
 *
 * One instance per reader; every reader drifts differently.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ReaderClock {

    private static final Logger logger = LoggerFactory.getLogger(ReaderClock.class);

    public enum Mode {
        // Correct every reader timestamp to the Pi's clock
        DELTA,
        // Use the reader's UTC timestamps as-is, but warn if they are off
        READER
    }

    /**
     * Reader timestamps are either UTC (microseconds since the epoch) or Uptime
     * (microseconds since the reader booted). Each kind gets its own estimator.
     */
    public enum Kind {
        UTC, UPTIME
    }

    private static final long WINDOW_SECONDS = 60;
    private static final long SKEW_WARN_MICROS = 1_000_000L;
    private static final long SKEW_WARN_INTERVAL_MICROS = 60_000_000L;
    private static final long CHANGE_LOG_MICROS = 100_000L;

    private final Integer readerID;
    private final Mode mode;
    private final String skewAdvice;
    private final Estimator utc = new Estimator(Kind.UTC);
    private final Estimator uptime = new Estimator(Kind.UPTIME);

    private long lastSkewWarnMicros = Long.MIN_VALUE / 2;
    private boolean warnedUptimeInReaderMode = false;

    /**
     * @param skewAdvice appended to the warning logged in READER mode when the
     * reader's clock is more than a second off
     */
    public ReaderClock(Integer readerID, Mode mode, String skewAdvice) {
        this.readerID = readerID;
        this.mode = mode;
        this.skewAdvice = skewAdvice == null ? "" : skewAdvice;
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
        warnedUptimeInReaderMode = false;
    }

    /**
     * Record a reader timestamp along with the Pi time it was received at.
     */
    public synchronized void sample(Kind kind, long readerMicros, long piReceiveMicros) {
        estimator(kind).add(piReceiveMicros - readerMicros, piReceiveMicros);
        if (kind == Kind.UTC && mode == Mode.READER) {
            checkSkew(piReceiveMicros);
        }
    }

    /**
     * Convert a reader timestamp into Pi epoch microseconds.
     */
    public synchronized long toEpochMicros(Kind kind, long readerMicros, long piReceiveMicros) {
        if (kind == Kind.UTC && mode == Mode.READER) {
            return readerMicros;
        }
        if (kind == Kind.UPTIME && mode == Mode.READER && !warnedUptimeInReaderMode) {
            logger.warn("Reader {} reports uptime-based timestamps, so its clock can't be used as-is. Using the Pi clock delta instead.", readerID);
            warnedUptimeInReaderMode = true;
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
            return describeOffset(utc.estimate) + (mode == Mode.READER ? " (reader time is used as-is)" : " (tag times are corrected to Pi time)");
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

    private void checkSkew(long piMicros) {
        Long offset = utc.estimate;
        if (offset != null && Math.abs(offset) > SKEW_WARN_MICROS && piMicros - lastSkewWarnMicros > SKEW_WARN_INTERVAL_MICROS) {
            logger.warn("Reader {}: {}. {}", readerID, describeOffset(offset), skewAdvice);
            lastSkewWarnMicros = piMicros;
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
