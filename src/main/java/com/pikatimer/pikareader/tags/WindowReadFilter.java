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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The strongest read in each window. A tag's window opens on its first read
 * and closes windowMs later; the next read after that opens a new one. A tag
 * that stays in view is reported once per window.
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

        final long closesAt;
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
            // The window closed before ready() got to it. This read starts the next one.
            out.add(w.best);
            w = null;
        }
        if (w == null) {
            windows.put(key, new Window(now + windowMs, read));
        } else if (ReadFilter.rssi(read) > ReadFilter.rssi(w.best)) {
            w.best = read;
        }
    }

    @Override
    public List<TagRead> ready(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            try {
                if (now >= e.getValue().closesAt) {
                    out.add(e.getValue().best);
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
        windows.values().forEach(w -> reads.add(w.best));
        out.clear();
        windows.clear();
        return reads;
    }

    @Override
    public boolean isIdle() {
        return windows.isEmpty() && out.isEmpty();
    }
}
