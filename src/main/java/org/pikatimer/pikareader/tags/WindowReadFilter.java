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
 * The strongest read in each window. A tag's window opens on its first read
 * and closes windowMs later. While the tag stays in view the windows follow
 * back to back, each starting where the last one ended, so a tag that stays in
 * view is reported exactly once per window and the processor's loop delay
 * doesn't add up. Once a whole window passes without a read the tag is
 * forgotten, and its next read opens a new window.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
final class WindowReadFilter implements ReadFilter {

    private static final Logger logger = LoggerFactory.getLogger(WindowReadFilter.class);

    private final long windowMs;
    private final ReaderGatingStyle style;
    private final Map<String, Window> windows = new HashMap<>();
    private final List<TagRead> out = new ArrayList<>();

    private static final class Window {

        long closesAt;
        // The strongest read so far. Null for the window right after a
        // reported one until the tag is read again: it is kept so the next
        // window starts where the last one ended.
        TagRead best;

        Window(long closesAt, TagRead first) {
            this.closesAt = closesAt;
            this.best = first;
        }
    }

    WindowReadFilter(long windowMs, ReaderGatingStyle style) {
        this.windowMs = windowMs;
        this.style = style;
    }

    @Override
    public void add(TagRead read, long now) {
        String key = ReadFilter.key(read, style);
        Window w = windows.get(key);
        if (w != null && now >= w.closesAt) {
            // The window closed before ready() got to it
            if (w.best != null) {
                out.add(w.best);
            }
            // This read belongs to the window right after it, unless that
            // one is over too
            long next = w.closesAt + windowMs;
            if (now < next) {
                w.closesAt = next;
                w.best = null;
            } else {
                w = null;
            }
        }
        if (w == null) {
            windows.put(key, new Window(now + windowMs, read));
        } else if (w.best == null || ReadFilter.rssi(read) > ReadFilter.rssi(w.best)) {
            w.best = read;
        }
    }

    @Override
    public List<TagRead> ready(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            Window w = e.getValue();
            try {
                if (now < w.closesAt) {
                    continue;
                }
                if (w.best == null) {
                    // A whole window without a read: the tag is gone
                    it.remove();
                    continue;
                }
                out.add(w.best);
                // Keep the next window so it starts on time
                w.best = null;
                w.closesAt += windowMs;
                if (now >= w.closesAt) {
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

    @Override
    public List<TagRead> flush() {
        List<TagRead> reads = new ArrayList<>(out);
        windows.values().stream().filter(w -> w.best != null).forEach(w -> reads.add(w.best));
        out.clear();
        windows.clear();
        return reads;
    }

    @Override
    public boolean isIdle() {
        // A window without a read needs no timer: the next read, or the
        // next ready(), clears it out
        return out.isEmpty() && windows.values().stream().allMatch(w -> w.best == null);
    }
}
