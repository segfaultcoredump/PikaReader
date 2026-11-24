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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rewind lookups against a DB laid out as TagDB writes it: folders named in
 * the system zone at write time, reads labelled in the configured Timezone.
 */
class TagDBRewindTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/HH-mm-ss.SSS'.dat'");
    private static final long MINUTE = 60_000L;
    /** Files are written a few seconds after their reads */
    private static final long WRITE_LAG = 2_000L;

    @TempDir
    Path db;

    private final List<TagRead> memory = new ArrayList<>();
    private final List<TagRead> all = new ArrayList<>();
    private int chip = 1;

    private static long ms(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    /** Store a read taken at epochMs, labelled in labelZone, in a dirZone folder. */
    private TagRead store(long epochMs, ZoneId labelZone, ZoneId dirZone, long lag) throws IOException {
        Instant at = Instant.ofEpochMilli(epochMs);
        TagRead r = new TagRead();
        r.setEPC(Integer.toHexString(chip++));
        r.setReaderID(1);
        r.setReaderAntenna(1);
        r.setPeakRSSI(-50.0);
        r.setEpochMilli(epochMs);
        r.setTimestamp(LocalDateTime.ofInstant(at, labelZone));
        r.setTZOffset(labelZone.getRules().getOffset(at).toString());

        long written = epochMs + lag;
        Path f = db.resolve(LocalDateTime.ofInstant(Instant.ofEpochMilli(written), dirZone).format(FILE));
        // Like TagDB.writeFile: a name already used (the fall-back hour) moves on a ms
        while (Files.exists(f)) {
            written++;
            f = db.resolve(LocalDateTime.ofInstant(Instant.ofEpochMilli(written), dirZone).format(FILE));
        }
        Files.createDirectories(f.getParent());
        Files.writeString(f, r.toJSON() + System.lineSeparator(), StandardCharsets.UTF_8);
        all.add(r);
        return r;
    }

    /** Store a read every step from from (inclusive) to to (exclusive). */
    private void storeEvery(long fromMs, long toMs, long step, ZoneId labelZone, ZoneId dirZone) throws IOException {
        for (long t = fromMs; t < toMs; t += step) {
            store(t, labelZone, dirZone, WRITE_LAG);
        }
    }

    private List<TagRead> between(ZoneId dirZone, long memoryFrom, long fromMs, long toMs) {
        return TagFiles.between(db, dirZone, memoryFrom, memory::forEach, fromMs, toMs);
    }

    private List<TagRead> betweenLabels(ZoneId dirZone, long memoryFrom, LocalDateTime from, LocalDateTime to, ZoneId labelZone) {
        return TagFiles.betweenLabels(db, dirZone, memoryFrom, memory::forEach, from, to, labelZone, System.currentTimeMillis());
    }

    private List<Long> epochs(List<TagRead> reads) {
        return reads.stream().map(TagRead::getEpochMilli).toList();
    }

    private List<Long> expected(long fromMs, long toMs) {
        return all.stream().map(TagRead::getEpochMilli).filter(e -> e >= fromMs && e < toMs).sorted().toList();
    }

    @Test
    void findsReadsWhenTheTimezoneSettingDiffersFromTheSystemZone() throws IOException {
        // Timezone = UTC, but the folders are named in Chicago time (UTC-5 in June)
        storeEvery(ms("2026-06-10T00:00:00Z"), ms("2026-06-11T00:00:00Z"), 10 * MINUTE, ZoneOffset.UTC, CHICAGO);

        long from = ms("2026-06-10T14:00:00Z");
        long to = ms("2026-06-10T16:00:00Z");
        List<TagRead> got = between(CHICAGO, Long.MAX_VALUE, from, to);
        assertEquals(12, got.size());
        assertEquals(expected(from, to), epochs(got));

        // The ISO form matches the UTC labels and still finds the Chicago folders
        List<TagRead> labelled = betweenLabels(CHICAGO, Long.MAX_VALUE,
                LocalDateTime.of(2026, 6, 10, 13, 59), LocalDateTime.of(2026, 6, 10, 15, 51), ZoneOffset.UTC);
        assertEquals(expected(from, to), epochs(labelled));
    }

    @Test
    void theIsoFormFindsReadsLabelledBeforeTheTimezoneSettingChanged() throws IOException {
        // Taken with Timezone = AUTO (labels in the system zone), and the
        // setting has since been changed to UTC
        storeEvery(ms("2026-06-10T00:00:00Z"), ms("2026-06-11T00:00:00Z"), 10 * MINUTE, CHICAGO, CHICAGO);
        List<TagRead> labelled = betweenLabels(CHICAGO, Long.MAX_VALUE,
                LocalDateTime.of(2026, 6, 10, 9, 59), LocalDateTime.of(2026, 6, 10, 11, 0), ZoneOffset.UTC);
        // 10:00-10:50 Chicago (CDT) = 15:00-15:50 UTC
        assertEquals(expected(ms("2026-06-10T15:00:00Z"), ms("2026-06-10T16:00:00Z")), epochs(labelled));
    }

    @Test
    void anEpochRangeInTheFallBackHourGetsOnlyThatPass() throws IOException {
        // 2026-11-01: 01:00-02:00 Chicago time happens twice (CDT 06-07Z, then CST 07-08Z)
        storeEvery(ms("2026-11-01T05:00:00Z"), ms("2026-11-01T09:00:00Z"), 10 * MINUTE, CHICAGO, CHICAGO);

        long firstPass = ms("2026-11-01T06:00:00Z");
        long secondPass = ms("2026-11-01T07:00:00Z");
        List<TagRead> first = between(CHICAGO, Long.MAX_VALUE, firstPass, secondPass);
        assertEquals(6, first.size());
        assertEquals(expected(firstPass, secondPass), epochs(first));
        assertTrue(first.stream().allMatch(r -> r.getTimestamp().getHour() == 1));

        List<TagRead> second = between(CHICAGO, Long.MAX_VALUE, secondPass, ms("2026-11-01T08:00:00Z"));
        assertEquals(expected(secondPass, ms("2026-11-01T08:00:00Z")), epochs(second));

        // The ISO form matches the 01:xx label, so both passes, in real-time order
        List<TagRead> labelled = betweenLabels(CHICAGO, Long.MAX_VALUE,
                LocalDateTime.of(2026, 11, 1, 0, 59), LocalDateTime.of(2026, 11, 1, 2, 0), CHICAGO);
        assertEquals(expected(firstPass, ms("2026-11-01T08:00:00Z")), epochs(labelled));
    }

    @Test
    void nothingIsMissedAcrossTheSpringForwardGap() throws IOException {
        // 2026-03-08: Chicago skips from 02:00 CST to 03:00 CDT
        storeEvery(ms("2026-03-08T05:00:00Z"), ms("2026-03-08T12:00:00Z"), 7 * MINUTE, CHICAGO, CHICAGO);
        long from = ms("2026-03-08T06:30:00Z");
        long to = ms("2026-03-08T10:30:00Z");
        assertEquals(expected(from, to), epochs(between(CHICAGO, Long.MAX_VALUE, from, to)));
    }

    @Test
    void theRangeIncludesFromAndExcludesTo() throws IOException {
        storeEvery(ms("2026-06-10T12:00:00Z"), ms("2026-06-10T13:00:00Z"), MINUTE, CHICAGO, CHICAGO);
        long from = ms("2026-06-10T12:10:00Z");
        long to = ms("2026-06-10T12:20:00Z");
        List<Long> got = epochs(between(CHICAGO, Long.MAX_VALUE, from, to));
        assertEquals(10, got.size());
        assertEquals(from, got.get(0));
        assertFalse(got.contains(to));
    }

    @Test
    void aReadWrittenInTheNextHoursFolderIsFound() throws IOException {
        TagRead late = store(ms("2026-06-10T14:59:59.000Z"), CHICAGO, CHICAGO, 5_000L);
        assertEquals(List.of(late.getEpochMilli()),
                epochs(between(CHICAGO, Long.MAX_VALUE, ms("2026-06-10T14:00:00Z"), ms("2026-06-10T15:00:00Z"))));
    }

    @Test
    void memoryAndDiskAreSplitSoEachReadComesOnce() throws IOException {
        // The startup day began at memoryFrom. Every read from then on is in
        // memory, and so are the earlier ones written into today's folder.
        long memoryFrom = LocalDateTime.of(2026, 6, 11, 0, 0).atZone(CHICAGO).toInstant().toEpochMilli();
        storeEvery(memoryFrom - 2 * 3_600_000L, memoryFrom + 2 * 3_600_000L, 5 * MINUTE, CHICAGO, CHICAGO);
        TagRead straddler = store(memoryFrom - 1_000L, CHICAGO, CHICAGO, 4_000L);
        for (TagRead r : all) {
            if (r.getEpochMilli() >= memoryFrom || r == straddler) {
                memory.add(r);
            }
        }
        long from = memoryFrom - 3_600_000L;
        long to = memoryFrom + 3_600_000L;
        List<TagRead> got = between(CHICAGO, memoryFrom, from, to);
        assertEquals(expected(from, to), epochs(got));
        assertEquals(got.size(), new HashSet<>(got).size());

        // Open-ended, from long ago
        assertEquals(expected(0, Long.MAX_VALUE), epochs(between(CHICAGO, memoryFrom, 0, Long.MAX_VALUE)));

        // The ISO form, open-ended, also takes each read once
        List<TagRead> labelled = betweenLabels(CHICAGO, memoryFrom, LocalDateTime.of(2026, 6, 10, 23, 0), null, CHICAGO);
        assertEquals(expected(memoryFrom - 3_600_000L + 1, Long.MAX_VALUE), epochs(labelled));
    }

    @Test
    void onlyTheFoldersForTheRangeAreRead() throws IOException {
        storeEvery(ms("2026-06-01T00:00:00Z"), ms("2026-06-05T00:00:00Z"), 3_600_000L, CHICAGO, CHICAGO);
        List<Path> dirs = TagFiles.hourDirs(db, CHICAGO, ms("2026-06-02T12:00:00Z"), ms("2026-06-02T13:00:00Z"));
        // 07:00 Chicago, with an hour of margin either side: 06, 07, 08
        assertEquals(List.of("2026/06/02/06", "2026/06/02/07", "2026/06/02/08"),
                dirs.stream().map(p -> db.relativize(p).toString()).toList());
    }
}
