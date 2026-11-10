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

import com.pikatimer.pikareader.readers.ReaderGatingStyle;
import com.pikatimer.pikareader.readers.profiles.ReaderProfile;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.DoubleUnaryOperator;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs simulated tag reads through the read filters the way
 * TagReadProcessor.run() does, and counts the reads sent to the timing
 * software. The read rates match a Speedway R420 field log: about 90 raw
 * reads a second, handled every 100-150 ms.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
class ReadFilterTest {

    // Local and reader clocks start here (any fixed time will do)
    private static final long T0 = 1_800_000_000_000L;
    private static final long MINUTE = 60_000;

    private static final String EPC = "E28011700000020A1B2C3D01";

    // ---------------------------------------------------------------------
    // Window filters: Start Mats, Start Panels, Finish Mats
    // ---------------------------------------------------------------------
    @Test
    void startMatsReportsEachStaticTagOncePerSecond() {
        List<Raw> raws = new ArrayList<>();
        for (int tag = 0; tag < 5; tag++) {
            raws.addAll(steady(epc(tag), 1, 1, -55, 3, tag * 10, MINUTE, 50, tag));
        }
        Run run = process(profile("start_mats"), null, ReaderGatingStyle.READER, raws, MINUTE);

        assertEquals(5, run.keys().size());
        for (String key : run.keys()) {
            int n = run.count(key);
            assertTrue(n >= 59 && n <= 61, key + ": expected about 60 reads in 60 s, got " + n);
            for (long gap : run.gaps(key)) {
                assertTrue(gap >= 750 && gap <= 1250, key + ": reads " + gap + " ms apart");
            }
        }
    }

    @ParameterizedTest(name = "{0} (Finish Mats Gating {1}) sends {2} reads a minute")
    @CsvSource({
        "start_mats,         , 60",
        "start_side_panels,  , 20",
        "finish_mats,        , 30",
        "finish_mats,     1.5, 40",
        "finish_mats,       3, 20"
    })
    void windowProfilesSendTheExpectedReadsPerMinute(String profile, String gating, int expected) {
        JSONObject readerConfig = new JSONObject();
        if (gating != null) {
            readerConfig.put(ReadFilterSettings.FINISH_MATS_GATING, Double.parseDouble(gating));
        }
        List<Raw> raws = steady(EPC, 1, 1, -55, 3, 0, MINUTE, 11, 1);
        Run run = process(profile(profile), readerConfig, ReaderGatingStyle.READER, raws, MINUTE);

        int n = run.count(onlyKey(run));
        assertTrue(Math.abs(n - expected) <= 1, profile + ": expected " + expected + " reads in 60 s, got " + n);
    }

    @Test
    void windowSendsTheStrongestReadOfEachWindow() {
        // A strong read every 250 ms, weak reads in between
        List<Raw> raws = new ArrayList<>();
        for (long t = 0; t < 10_000; t += 50) {
            raws.add(new Raw(t, read(EPC, 1, 1, t % 250 == 0 ? -40 : -60, t)));
        }
        Run run = process(profile("start_mats"), null, ReaderGatingStyle.READER, raws, 10_000);

        assertFalse(run.reads(onlyKey(run)).isEmpty());
        run.reads(onlyKey(run)).forEach(r -> assertEquals(-40.0, r.getPeakRSSI()));
    }

    @Test
    void windowForgetsATagThatLeavesAndStartsOverWhenItReturns() {
        // In view 0-5 s, gone 5-15 s, back 15-20 s
        List<Raw> raws = new ArrayList<>(steady(EPC, 1, 1, -55, 3, 0, 5_000, 20, 1));
        raws.addAll(steady(EPC, 1, 1, -55, 3, 15_000, 20_000, 20, 2));
        Run run = process(profile("start_mats"), null, ReaderGatingStyle.READER, raws, 30_000);

        String key = onlyKey(run);
        List<Long> times = run.times(key);
        // The last batch before the tag left can land in the next window,
        // which closes up to a second later
        assertTrue(times.stream().noneMatch(t -> t > 6_500 && t < 16_000), "reads while the tag was gone: " + times);
        int n = run.count(key);
        assertTrue(n >= 10 && n <= 12, "expected 5-6 reads for each 5 s in view, got " + n + " at " + times);
    }

    @Test
    void gatingStyleSetsWhatCountsAsOneTag() {
        // One tag seen by reader 1 on antennas 1 and 2, and by reader 2
        List<Raw> raws = new ArrayList<>(steady(EPC, 1, 1, -55, 3, 0, MINUTE, 30, 1));
        raws.addAll(steady(EPC, 1, 2, -58, 3, 10, MINUTE, 30, 2));
        raws.addAll(steady(EPC, 2, 1, -60, 3, 20, MINUTE, 30, 3));

        assertEquals(3, process(profile("start_mats"), null, ReaderGatingStyle.ANTENNA, raws, MINUTE).keys().size());
        assertEquals(2, process(profile("start_mats"), null, ReaderGatingStyle.READER, raws, MINUTE).keys().size());
        Run box = process(profile("start_mats"), null, ReaderGatingStyle.BOX, raws, MINUTE);
        assertEquals(1, box.keys().size());
        int n = box.count(onlyKey(box));
        assertTrue(n >= 59 && n <= 61, "BOX: expected about 60 reads, got " + n);
    }

    @Test
    void windowFlushAndIdle() {
        ReadFilter f = filter(profile("finish_mats"), null, ReaderGatingStyle.READER);
        assertTrue(f.isIdle());
        f.add(read(EPC, 1, 1, -50, T0), T0);
        assertFalse(f.isIdle(), "a window is waiting to close");
        assertTrue(f.ready(T0 + 1_000).isEmpty());
        assertEquals(1, f.ready(T0 + 2_000).size());
        assertTrue(f.isIdle(), "nothing read since the window closed");
        f.add(read(EPC, 1, 1, -50, T0 + 2_100), T0 + 2_100);
        assertEquals(1, f.flush().size());
        assertTrue(f.isIdle());
        assertTrue(f.ready(T0 + 10_000).isEmpty());
    }

    // ---------------------------------------------------------------------
    // RSSI peak filter: Finish Panels, Split Side Panels
    // ---------------------------------------------------------------------
    @ParameterizedTest
    @CsvSource({"finish_side_panels", "split_side_panels"})
    void panelsReportAStaticTagEvery15Seconds(String profile) {
        // Seen by two antennas at different levels, about 90 reads a second
        List<Raw> raws = new ArrayList<>(steady(EPC, 1, 1, -50, 2, 0, MINUTE, 20, 1));
        raws.addAll(steady(EPC, 1, 2, -62, 2, 10, MINUTE, 25, 2));
        Run run = process(profile(profile), null, ReaderGatingStyle.READER, raws, MINUTE);

        String key = onlyKey(run);
        int n = run.count(key);
        assertTrue(n >= 3 && n <= 4, "expected 3-4 reads in 60 s, got " + n + " at " + run.times(key));
        assertTrue(run.times(key).getFirst() >= 15_000, "first read sent too soon: " + run.times(key));
        assertSpacedAtLeast15s(run, key);
    }

    @Test
    void panelsReportATagThatKeepsDroppingOutAtMostEvery15Seconds() {
        // A 200 ms burst of reads every 5 s, a tag on the edge of the field
        List<Raw> raws = new ArrayList<>();
        for (long burst = 0; burst < MINUTE; burst += 5_000) {
            raws.addAll(steady(EPC, 1, 1, -65, 2, burst, burst + 200, 20, burst));
        }
        Run run = process(profile("split_side_panels"), null, ReaderGatingStyle.READER, raws, MINUTE);

        String key = onlyKey(run);
        int n = run.count(key);
        assertTrue(n >= 3 && n <= 4, "expected 3-4 reads in 60 s, got " + n + " at " + run.times(key));
        assertSpacedAtLeast15s(run, key);
    }

    @Test
    void panelsReportAPassingRunnerOnceNearTheClosestApproach() {
        // RSSI rises 5 dB/s to -45 dBm at 5 s, then falls away until 10 s
        List<Raw> raws = shaped(t -> -45 - 5 * Math.abs(t / 1000.0 - 5), 0, 10_000, 30, 7);
        Run run = process(profile("split_side_panels"), null, ReaderGatingStyle.READER, raws, 40_000);

        String key = onlyKey(run);
        assertEquals(1, run.count(key), "reads at " + run.times(key));
        TagRead r = run.reads(key).getFirst();
        assertTrue(r.getPeakRSSI() >= -46.5, "sent a " + r.getPeakRSSI() + " dBm read, not the peak");
        long readAt = r.getEpochMilli() - T0;
        assertTrue(readAt >= 4_000 && readAt <= 6_000, "sent the read from " + readAt + " ms");
        assertTrue(run.times(key).getFirst() <= 9_000, "sent at " + run.times(key).getFirst() + " ms");
    }

    @Test
    void panelsReportALingeringFinisherOnceAPassAndThenEvery15Seconds() {
        // Peaks at 5 s, then stands in the chute at -60 dBm
        List<Raw> raws = shaped(t -> t < 8_000 ? -45 - 5 * Math.abs(t / 1000.0 - 5) : -60, 0, MINUTE, 30, 9);
        Run run = process(profile("finish_side_panels"), null, ReaderGatingStyle.READER, raws, MINUTE);

        String key = onlyKey(run);
        List<Long> times = run.times(key);
        assertTrue(times.getFirst() <= 9_000, "first read sent at " + times.getFirst() + " ms");
        assertTrue(run.reads(key).getFirst().getPeakRSSI() >= -46.5, "first read isn't the peak");
        assertEquals(4, times.size(), "reads at " + times);
        assertSpacedAtLeast15s(run, key);
    }

    @Test
    void panelsStartOverForTheNextLap() {
        // Two passes 60 s apart
        List<Raw> raws = shaped(t -> -45 - 5 * Math.abs(t / 1000.0 - 5), 0, 10_000, 30, 11);
        shaped(t -> -45 - 5 * Math.abs(t / 1000.0 - 65), 60_000, 70_000, 30, 12).forEach(raws::add);
        Run run = process(profile("split_side_panels"), null, ReaderGatingStyle.READER, raws, 90_000);

        assertEquals(2, run.count(onlyKey(run)), "reads at " + run.times(onlyKey(run)));
    }

    @Test
    void panelsFlushAndIdle() {
        ReadFilter f = filter(profile("split_side_panels"), null, ReaderGatingStyle.READER);
        assertTrue(f.isIdle());
        f.add(read(EPC, 1, 1, -50, T0), T0);
        assertFalse(f.isIdle(), "the tag is being followed");
        assertEquals(1, f.ready(T0 + 3_100).size(), "unseen for 3 s");
        assertTrue(f.isIdle(), "reported, nothing read since");

        // Still in view: ignored until 15 s after the report, then sent again
        for (long t = 3_200; t < 18_100; t += 1_000) {
            f.add(read(EPC, 1, 1, -50, T0 + t), T0 + t);
            assertTrue(f.isIdle(), "the tail of a pass is ignored");
            assertTrue(f.ready(T0 + t).isEmpty());
        }
        f.add(read(EPC, 1, 1, -50, T0 + 18_100), T0 + 18_100);
        assertFalse(f.isIdle(), "still in view 15 s after the report");
        assertEquals(1, f.ready(T0 + 18_100).size());
        assertTrue(f.isIdle());
        assertTrue(f.flush().isEmpty());

        // Gone for 3 s and 15 s since the last report: the next read is a new pass
        f.add(read(EPC, 1, 1, -50, T0 + 40_000), T0 + 40_000);
        assertFalse(f.isIdle(), "a new pass is being followed");
        assertEquals(1, f.flush().size());
    }

    // ---------------------------------------------------------------------
    // Profile -> filter
    // ---------------------------------------------------------------------
    @Test
    void eachProfileGetsItsFilter() {
        assertEquals(ReadFilterSettings.window(1_000), ReadFilterSettings.forProfile(profile("start_mats"), null));
        assertEquals(ReadFilterSettings.window(3_000), ReadFilterSettings.forProfile(profile("start_side_panels"), null));
        assertEquals(ReadFilterSettings.window(2_000), ReadFilterSettings.forProfile(profile("finish_mats"), null));
        assertEquals(ReadFilterSettings.window(2_000), ReadFilterSettings.forProfile(profile("finish_mats"), new JSONObject()));
        assertEquals(ReadFilterSettings.window(2_500),
                ReadFilterSettings.forProfile(profile("finish_mats"), new JSONObject().put(ReadFilterSettings.FINISH_MATS_GATING, 2.5)));
        assertEquals(ReadFilterSettings.window(2_000),
                ReadFilterSettings.forProfile(profile("finish_mats"), new JSONObject().put(ReadFilterSettings.FINISH_MATS_GATING, "soon")));
        assertEquals(ReadFilterSettings.window(2_000),
                ReadFilterSettings.forProfile(profile("finish_mats"), new JSONObject().put(ReadFilterSettings.FINISH_MATS_GATING, 0)));
        assertEquals(ReadFilterSettings.window(2_000), ReadFilterSettings.forProfile(profile("no_such_profile"), null));
        for (String id : List.of("finish_side_panels", "split_side_panels")) {
            ReadFilterSettings s = ReadFilterSettings.forProfile(profile(id), null);
            assertEquals(ReadFilterSettings.Type.RSSI_PEAK, s.type());
            assertEquals(15_000, s.maxHoldMs());
            assertEquals(15_000, s.repeatMs());
            assertEquals(3_000, s.lostMs());
        }
    }

    // ---------------------------------------------------------------------
    // Simulation
    // ---------------------------------------------------------------------
    /**
     * A raw read and when it reaches the processor, in ms from the start.
     */
    private record Raw(long at, TagRead read) {

    }

    /**
     * The reads sent on, per key, with when they were sent (ms from the
     * start).
     */
    private static final class Run {

        private final Map<String, List<Long>> times = new TreeMap<>();
        private final Map<String, List<TagRead>> reads = new TreeMap<>();

        void record(String key, long at, TagRead r) {
            times.computeIfAbsent(key, k -> new ArrayList<>()).add(at);
            reads.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<String> keys() {
            return new ArrayList<>(times.keySet());
        }

        int count(String key) {
            return times.getOrDefault(key, List.of()).size();
        }

        List<Long> times(String key) {
            return times.getOrDefault(key, List.of());
        }

        List<TagRead> reads(String key) {
            return reads.getOrDefault(key, List.of());
        }

        // How far apart the sent reads' own timestamps are
        List<Long> readGaps(String key) {
            List<TagRead> r = reads(key);
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < r.size(); i++) {
                gaps.add(r.get(i).getEpochMilli() - r.get(i - 1).getEpochMilli());
            }
            return gaps;
        }

        List<Long> gaps(String key) {
            List<Long> t = times(key);
            List<Long> gaps = new ArrayList<>();
            for (int i = 1; i < t.size(); i++) {
                gaps.add(t.get(i) - t.get(i - 1));
            }
            return gaps;
        }
    }

    /**
     * Feed the reads through a new filter the way TagReadProcessor.run()
     * does: block while the filter is idle, otherwise poll for up to 100 ms;
     * take everything queued; add() it in timestamp order; ready(); then
     * sleep 100 ms (plus 0-50 ms of processing time). Stops at durationMs.
     */
    private static Run process(ReaderProfile profile, JSONObject readerConfig, ReaderGatingStyle style, List<Raw> raws, long durationMs) {
        ReadFilter filter = filter(profile, readerConfig, style);
        List<Raw> queue = new ArrayList<>(raws);
        queue.sort(Comparator.comparingLong(Raw::at));
        Random processing = new Random(42);
        Run run = new Run();

        long now = 0;
        int i = 0;
        while (true) {
            long next = i < queue.size() ? queue.get(i).at() : Long.MAX_VALUE;
            if (filter.isIdle()) {
                // take()
                if (next > durationMs) {
                    break;
                }
                now = Math.max(now, next);
            } else {
                // poll(100 ms)
                now = Math.min(now + 100, Math.max(now, next));
            }
            if (now > durationMs) {
                break;
            }
            List<TagRead> batch = new ArrayList<>();
            while (i < queue.size() && queue.get(i).at() <= now) {
                batch.add(queue.get(i++).read());
            }
            batch.sort(null);
            for (TagRead r : batch) {
                filter.add(r, T0 + now);
            }
            for (TagRead r : filter.ready(T0 + now)) {
                run.record(ReadFilter.key(r, style), now, r);
            }
            now += 100 + processing.nextInt(51);
        }
        return run;
    }

    private static ReadFilter filter(ReaderProfile profile, JSONObject readerConfig, ReaderGatingStyle style) {
        return ReadFilter.create(ReadFilterSettings.forProfile(profile, readerConfig), style);
    }

    private static ReaderProfile profile(String id) {
        return new ReaderProfile(id, id, "", 16, 1);
    }

    /**
     * A tag read every periodMs from fromMs to toMs at about rssi dBm, give
     * or take noiseDb.
     */
    private static List<Raw> steady(String epc, int reader, int antenna, double rssi, double noiseDb,
            long fromMs, long toMs, long periodMs, long seed) {
        Random rnd = new Random(seed);
        List<Raw> raws = new ArrayList<>();
        for (long t = fromMs; t < toMs; t += periodMs) {
            double r = Math.round((rssi + (rnd.nextDouble() * 2 - 1) * noiseDb) * 2) / 2.0;
            raws.add(new Raw(t, read(epc, reader, antenna, r, T0 + t)));
        }
        return raws;
    }

    /**
     * A tag read every periodMs from fromMs to toMs, with the RSSI following
     * rssiAt(ms) give or take 1.5 dB.
     */
    private static List<Raw> shaped(DoubleUnaryOperator rssiAt, long fromMs, long toMs, long periodMs, long seed) {
        Random rnd = new Random(seed);
        List<Raw> raws = new ArrayList<>();
        for (long t = fromMs; t < toMs; t += periodMs) {
            double r = Math.round((rssiAt.applyAsDouble(t) + (rnd.nextDouble() * 2 - 1) * 1.5) * 2) / 2.0;
            raws.add(new Raw(t, read(EPC, 1, 1, r, T0 + t)));
        }
        return raws;
    }

    private static TagRead read(String epc, int reader, int antenna, double rssi, long epochMilli) {
        TagRead r = new TagRead();
        r.setEPC(epc);
        r.setReaderID(reader);
        r.setReaderAntenna(antenna);
        r.setPeakRSSI(rssi);
        r.setEpochMilli(epochMilli);
        r.setTZOffset("Z");
        r.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC));
        return r;
    }

    private static String epc(int tag) {
        return String.format("E28011700000020A1B2C3D%02X", tag);
    }

    /**
     * Sent at least 15 s apart, and the reads' own timestamps at least 15 s
     * apart give or take one pass of the processor loop.
     */
    private static void assertSpacedAtLeast15s(Run run, String key) {
        run.gaps(key).forEach(gap -> assertTrue(gap >= 15_000, "sent " + gap + " ms apart: " + run.times(key)));
        run.readGaps(key).forEach(gap -> assertTrue(gap >= 14_800, "read timestamps " + gap + " ms apart"));
    }

    private static String onlyKey(Run run) {
        assertEquals(1, run.keys().size(), "keys: " + run.keys());
        return run.keys().getFirst();
    }
}
