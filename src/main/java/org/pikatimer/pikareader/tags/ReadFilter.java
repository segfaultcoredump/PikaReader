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
import java.util.List;

/**
 * Turns the raw reads into the reads sent to the timing software ("cooked"
 * reads). Only used from the tag processing thread, so implementations are
 * not thread-safe.
 *
 * Times are the local clock (milliseconds) when the processor received the
 * read, so a reader's clock being off can't hold reads back or release them
 * early. The reads themselves keep the reader's timestamp.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public interface ReadFilter {

    /**
     * Add a raw read.
     *
     * @param read the read
     * @param now when the processor received it
     */
    void add(TagRead read, long now);

    /**
     * @return the reads that are ready to send, possibly empty
     */
    List<TagRead> ready(long now);

    /**
     * @return the best read of everything still pending, and forget it all
     */
    List<TagRead> flush();

    /**
     * @return true when nothing is waiting on a timer, so the processor can
     * wait for the next read
     */
    boolean isIdle();

    static ReadFilter create(ReadFilterSettings settings, ReaderGatingStyle style) {
        return switch (settings.type()) {
            case WINDOW ->
                new WindowReadFilter(settings.windowMs(), style);
            case RSSI_PEAK ->
                new RssiPeakReadFilter(settings, style);
        };
    }

    /**
     * What counts as one tag for the gating style: the EPC on the whole box,
     * per reader, or per reader antenna.
     */
    static String key(TagRead t, ReaderGatingStyle style) {
        return switch (style) {
            case BOX ->
                t.hexEPC;
            case READER ->
                t.hexEPC + "|" + t.getReaderID();
            case ANTENNA ->
                t.hexEPC + "|" + t.getReaderID() + "|" + t.getReaderAntenna();
        };
    }

    /**
     * The read's RSSI. A missing one counts as -100 dBm, as the Zebra driver
     * already does.
     */
    static double rssi(TagRead t) {
        return t.rssi == null ? -100.0 : t.rssi;
    }
}
