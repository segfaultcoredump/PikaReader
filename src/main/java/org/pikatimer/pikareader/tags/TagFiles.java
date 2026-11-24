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
package org.pikatimer.pikareader.tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finding stored reads for a rewind.
 *
 * The DB folders (yyyy/MM/dd/HH) are named in the system timezone at the time
 * each file was written, while a read's timestamp label is in the configured
 * Timezone at the time it was read. The two can differ, and either can change
 * offset (daylight saving) in the middle of a range. So folders are picked by
 * the span of real time (epoch ms) each one covers, never by a read's label.
 *
 * The reads in memory (TagDB's list: every read from the startup day on) and
 * on disk are split at memoryFrom by epochMilli, so each read is taken from
 * exactly one of them.
 */
final class TagFiles {

    private static final Logger logger = LoggerFactory.getLogger(TagFiles.class);

    /**
     * How far past a range's ends to look for folders. A file is written up
     * to a few seconds after its reads (and a reader clock may be a little
     * off), so a read can sit in the next hour's folder.
     */
    static final long MARGIN_MS = 3_600_000L;

    private static final Pattern YEAR = Pattern.compile("\\d{4}");
    private static final Pattern TWO_DIGITS = Pattern.compile("\\d{2}");

    /** The reads held in memory, iterated under their lock. */
    @FunctionalInterface
    interface MemoryReads {

        void forEach(Consumer<TagRead> action);
    }

    private TagFiles() {
    }

    // The first and last real times a local time can stand for in a zone
    private static long earliest(LocalDateTime t, ZoneId zone) {
        return t.atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
    }

    private static long latest(LocalDateTime t, ZoneId zone) {
        return t.atZone(zone).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
    }

    static long epoch(TagRead r) {
        Long ms = r.getEpochMilli();
        return ms == null ? 0 : ms;
    }

    /**
     * The hour folders, oldest first, that may hold reads taken in
     * [fromMs, toMs], with MARGIN_MS either side. Only folders that exist are
     * listed, so an open-ended range costs no more than the data on disk.
     */
    static List<Path> hourDirs(Path db, ZoneId dirZone, long fromMs, long toMs) {
        long lo = fromMs - MARGIN_MS;
        long hi = toMs > Long.MAX_VALUE - MARGIN_MS ? Long.MAX_VALUE : toMs + MARGIN_MS;
        List<Path> out = new ArrayList<>();
        for (Path y : subDirs(db, YEAR)) {
            int year = Integer.parseInt(y.getFileName().toString());
            if (!overlaps(dayStart(LocalDate.of(year, 1, 1), dirZone), dayStart(LocalDate.of(year + 1, 1, 1), dirZone), lo, hi)) {
                continue;
            }
            for (Path m : subDirs(y, TWO_DIGITS)) {
                int month = Integer.parseInt(m.getFileName().toString());
                if (month < 1 || month > 12) {
                    continue;
                }
                LocalDate first = LocalDate.of(year, month, 1);
                if (!overlaps(dayStart(first, dirZone), dayStart(first.plusMonths(1), dirZone), lo, hi)) {
                    continue;
                }
                for (Path d : subDirs(m, TWO_DIGITS)) {
                    LocalDate date;
                    try {
                        date = LocalDate.of(year, month, Integer.parseInt(d.getFileName().toString()));
                    } catch (DateTimeException ex) {
                        continue;
                    }
                    if (!overlaps(dayStart(date, dirZone), dayStart(date.plusDays(1), dirZone), lo, hi)) {
                        continue;
                    }
                    for (Path h : subDirs(d, TWO_DIGITS)) {
                        int hour = Integer.parseInt(h.getFileName().toString());
                        if (hour > 23) {
                            continue;
                        }
                        LocalDateTime local = date.atTime(hour, 0);
                        // A fall-back hour happens twice: it spans from its
                        // first start to the end of its second pass
                        long start = ZonedDateTime.ofLocal(local, dirZone, null).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
                        long end = ZonedDateTime.ofLocal(local.plusHours(1), dirZone, null).withLaterOffsetAtOverlap().toInstant().toEpochMilli();
                        if (overlaps(start, end, lo, hi)) {
                            out.add(h);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static long dayStart(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private static boolean overlaps(long start, long end, long lo, long hi) {
        return start < hi && end > lo;
    }

    private static List<Path> subDirs(Path dir, Pattern name) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> name.matcher(p.getFileName().toString()).matches() && Files.isDirectory(p))
                    .sorted().toList();
        } catch (IOException ex) {
            logger.error("Error reading DB at {}", dir.toAbsolutePath(), ex);
            return List.of();
        }
    }

    /** The reads in one DB file. Bad lines are logged and skipped. */
    static List<TagRead> readFile(Path f) {
        List<TagRead> reads = new ArrayList<>();
        try (Stream<String> lines = Files.lines(f)) {
            lines.forEach(s -> {
                try {
                    reads.add(new TagRead(new JSONObject(s)));
                } catch (JSONException | DateTimeException ex) {
                    logger.error("Error parsing {} in file at {}", s, f.toAbsolutePath());
                }
            });
        } catch (Exception ex) {
            logger.error("Error reading file at {}", f.toAbsolutePath(), ex);
        }
        return reads;
    }

    /** Add the reads in the folders for [fromMs, toMs] that pass keep. */
    private static void scan(Path db, ZoneId dirZone, long fromMs, long toMs, Predicate<TagRead> keep, List<TagRead> out) {
        for (Path hourDir : hourDirs(db, dirZone, fromMs, toMs)) {
            try (Stream<Path> files = Files.list(hourDir)) {
                files.filter(Files::isRegularFile).sorted().forEach(f -> {
                    for (TagRead r : readFile(f)) {
                        if (keep.test(r)) {
                            out.add(r);
                        }
                    }
                });
            } catch (IOException ex) {
                logger.error("Error reading DB at {}", hourDir.toAbsolutePath(), ex);
            }
        }
    }

    /**
     * The reads with epochMilli in [fromMs, toMs), sorted by time. Those from
     * memoryFrom on come from memory, the rest from disk.
     */
    static List<TagRead> between(Path db, ZoneId dirZone, long memoryFrom, MemoryReads memory, long fromMs, long toMs) {
        List<TagRead> out = new ArrayList<>();
        if (fromMs < memoryFrom) {
            long diskTo = Math.min(toMs, memoryFrom);
            scan(db, dirZone, fromMs, diskTo, r -> {
                long e = epoch(r);
                return e >= fromMs && e < diskTo;
            }, out);
        }
        if (toMs > memoryFrom) {
            long memFrom = Math.max(fromMs, memoryFrom);
            memory.forEach(r -> {
                long e = epoch(r);
                if (e >= memFrom && e < toMs) {
                    out.add(r);
                }
            });
        }
        out.sort(null);
        return out;
    }

    /**
     * The reads whose timestamp label is strictly between from and to (to
     * null: no end), sorted by time. A label is the local time in the
     * configured Timezone when the read was taken, so in a fall-back hour
     * both passes match.
     *
     * The folders are found by the real time the labels stand for, taken in
     * both the configured Timezone (labelZone) and the folders' own zone:
     * reads labelled under either one are found, e.g. reads taken with
     * Timezone = AUTO before the setting was changed. Labels made under some
     * third zone may be missed; an epoch range never is.
     */
    static List<TagRead> betweenLabels(Path db, ZoneId dirZone, long memoryFrom, MemoryReads memory,
            LocalDateTime from, LocalDateTime to, ZoneId labelZone, long nowMs) {
        long fromMs = Math.min(earliest(from, labelZone), earliest(from, dirZone));
        long toMs = to == null ? nowMs : Math.max(latest(to, labelZone), latest(to, dirZone));
        Predicate<TagRead> inLabelRange = r -> {
            LocalDateTime ts = r.getTimestamp();
            return ts != null && from.isBefore(ts) && (to == null || to.isAfter(ts));
        };
        List<TagRead> out = new ArrayList<>();
        if (fromMs - MARGIN_MS < memoryFrom) {
            scan(db, dirZone, fromMs, Math.min(toMs, memoryFrom), r -> epoch(r) < memoryFrom && inLabelRange.test(r), out);
        }
        if (to == null || toMs + MARGIN_MS > memoryFrom) {
            memory.forEach(r -> {
                if (epoch(r) >= memoryFrom && inLabelRange.test(r)) {
                    out.add(r);
                }
            });
        }
        out.sort(null);
        return out;
    }
}
