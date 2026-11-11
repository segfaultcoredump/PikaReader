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

import org.pikatimer.pikareader.readers.ReaderGatingStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For side panels, which can see a runner coming from a long way off. A tag
 * is followed while its RSSI rises, and its strongest read is reported once
 * the runner has passed the panel:
 * <ol>
 * <li>the leading antenna's averaged RSSI has stayed dropDb below its peak
 * for dropHoldMs, or</li>
 * <li>the leading antenna hasn't seen the tag for lostMs (including the tag
 * being gone), or</li>
 * <li>maxHoldMs have passed since the first read (a tag that stays in view
 * with a flat RSSI never drops off).</li>
 * </ol>
 * The RSSI trend is followed per reader antenna, since two antennas (or two
 * panels on opposite sides of the road) see the same tag at very different
 * levels. The leading antenna is the one with the highest averaged peak.
 *
 * After a report a tag is reported at most once every repeatMs:
 * <ul>
 * <li>its reads for repeatMs after the report are ignored, so the tail of a
 * pass doesn't produce more reads;</li>
 * <li>a tag still in view after that (a finisher standing in the chute, a bib
 * left next to the panel) is reported again with its first reads, and so on
 * every repeatMs;</li>
 * <li>the next pass (e.g. the next lap) starts over once the tag has been out
 * of view for lostMs and repeatMs have passed since the last report, so a tag
 * that keeps dropping out of view for a few seconds isn't reported on every
 * return.</li>
 * </ul>
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
final class RssiPeakReadFilter implements ReadFilter {

    private static final Logger logger = LoggerFactory.getLogger(RssiPeakReadFilter.class);

    private final ReadFilterSettings settings;
    private final ReaderGatingStyle style;
    private final Map<String, Track> tracks = new HashMap<>();
    private final List<TagRead> out = new ArrayList<>();

    /**
     * One reader antenna's view of a tag.
     */
    private static final class AntennaTrend {

        private final double[] recent;
        private int count;
        private int next;
        private double sum;
        double avg;
        // Only set once there are enough reads to average
        double peakAvg = Double.NEGATIVE_INFINITY;
        long lastSeen;
        // When avg went dropDb below peakAvg, or -1 while it isn't
        long belowSince = -1;

        AntennaTrend(int smoothingReads) {
            recent = new double[Math.max(1, smoothingReads)];
        }

        void add(double rssi, long now, double dropDb) {
            if (count == recent.length) {
                sum -= recent[next];
            } else {
                count++;
            }
            recent[next] = rssi;
            sum += rssi;
            next = (next + 1) % recent.length;
            avg = sum / count;
            if (count == recent.length && avg > peakAvg) {
                peakAvg = avg;
            }
            if (peakAvg != Double.NEGATIVE_INFINITY && avg <= peakAvg - dropDb) {
                if (belowSince < 0) {
                    belowSince = now;
                }
            } else {
                belowSince = -1;
            }
            lastSeen = now;
        }
    }

    private static final class Track {

        final long firstSeen;
        long lastSeen;
        TagRead best;
        // When the tag was last reported, or -1 while this pass hasn't been
        long reportedAt = -1;
        // Once repeatMs have passed since the last report: the strongest
        // read, sent by the next ready(). Null otherwise.
        TagRead since;
        final Map<String, AntennaTrend> antennas = new HashMap<>();

        Track(long now, TagRead first) {
            firstSeen = now;
            lastSeen = now;
            best = first;
        }
    }

    RssiPeakReadFilter(ReadFilterSettings settings, ReaderGatingStyle style) {
        this.settings = settings;
        this.style = style;
    }

    @Override
    public void add(TagRead read, long now) {
        String key = ReadFilter.key(read, style);
        Track t = tracks.get(key);
        if (t != null && t.reportedAt >= 0 && now - t.lastSeen > settings.lostMs()
                && now - t.reportedAt >= settings.repeatMs()) {
            // Reported, then out of view, and not reported too recently:
            // this is a new pass
            t = null;
        }
        if (t == null) {
            t = new Track(now, read);
            tracks.put(key, t);
        }
        t.lastSeen = now;
        if (t.reportedAt >= 0) {
            // Already reported. Ignore the tail of the pass, but once
            // repeatMs have passed, a tag still in view is reported again.
            if (now - t.reportedAt >= settings.repeatMs()
                    && (t.since == null || ReadFilter.rssi(read) > ReadFilter.rssi(t.since))) {
                t.since = read;
            }
            return;
        }

        double rssi = ReadFilter.rssi(read);
        if (rssi > ReadFilter.rssi(t.best)) {
            t.best = read;
        }
        t.antennas.computeIfAbsent(read.getReaderID() + "|" + read.getReaderAntenna(),
                k -> new AntennaTrend(settings.smoothingReads())).add(rssi, now, settings.dropDb());

        // Report as soon as the drop has lasted, not at the next ready()
        check(key, t, now);
    }

    @Override
    public List<TagRead> ready(long now) {
        Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Track> e = it.next();
            Track t = e.getValue();
            try {
                if (t.reportedAt < 0) {
                    check(e.getKey(), t, now);
                } else if (t.since != null) {
                    report(e.getKey(), t, t.since, "still in view", now);
                } else if (now - t.lastSeen > settings.lostMs() && now - t.reportedAt >= settings.repeatMs()) {
                    // Gone: forget it
                    it.remove();
                }
            } catch (RuntimeException ex) {
                logger.error("Dropping the pending reads for {}", e.getKey(), ex);
                it.remove();
            }
        }
        List<TagRead> reads = new ArrayList<>(out);
        out.clear();
        return reads;
    }

    private void check(String key, Track t, long now) {
        AntennaTrend lead = null;
        for (AntennaTrend a : t.antennas.values()) {
            if (lead == null || a.peakAvg > lead.peakAvg
                    || (a.peakAvg == lead.peakAvg && a.avg > lead.avg)) {
                lead = a;
            }
        }
        String reason = null;
        if (lead != null && lead.belowSince >= 0 && now - lead.belowSince >= settings.dropHoldMs()) {
            reason = "RSSI dropped";
        } else if (lead == null || now - lead.lastSeen > settings.lostMs()) {
            reason = "not seen";
        } else if (now - t.firstSeen >= settings.maxHoldMs()) {
            reason = "still in view";
        }
        if (reason != null) {
            report(key, t, t.best, reason, now);
        }
    }

    private void report(String key, Track t, TagRead read, String reason, long now) {
        logger.debug("Reporting {} ({}): {} dBm, {} ms after its first read", key, reason, ReadFilter.rssi(read), now - t.firstSeen);
        out.add(read);
        t.reportedAt = now;
        t.since = null;
    }

    @Override
    public List<TagRead> flush() {
        List<TagRead> reads = new ArrayList<>(out);
        tracks.values().forEach(t -> {
            if (t.reportedAt < 0) {
                reads.add(t.best);
            } else if (t.since != null) {
                reads.add(t.since);
            }
        });
        out.clear();
        tracks.clear();
        return reads;
    }

    @Override
    public boolean isIdle() {
        // Reported tracks with no reads since only need clearing out, which
        // add() and ready() do whenever reads come in
        return out.isEmpty() && tracks.values().stream().allMatch(t -> t.reportedAt >= 0 && t.since == null);
    }
}
