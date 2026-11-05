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
package com.pikatimer.pikareader.readers.impinj;

import java.util.Locale;

/**
 * Names for the Impinj RF mode numbers.
 *
 * Octane 6 removed its ReaderMode enum and takes the plain mode number
 * (Settings.setRfMode(Integer)), so the names that the config file and the
 * logs use live here. The values are the ones the old enum used.
 *
 * Newer readers (e.g. the R700) support modes that aren't listed here. Those
 * are used by number and are passed to the reader as-is.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public enum ImpinjRfMode {

    MaxThroughput(0),
    Hybrid(1),
    DenseReaderM4(2),
    DenseReaderM8(3),
    MaxMiller(4),
    DenseReaderM4Two(5),
    AutoSetDenseReader(1000),
    AutoSetDenseReaderDeepScan(1002),
    AutoSetStaticFast(1003),
    AutoSetStaticDRM(1004),
    AutoSetCustom(1005);

    private final int value;

    ImpinjRfMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /**
     * Names are matched ignoring case, spaces, dashes and underscores, so
     * "MaxMiller", "max miller" and "MAX_MILLER" all work.
     *
     * @return the mode, or null if the name is unknown
     */
    public static ImpinjRfMode fromName(String s) {
        if (s == null) {
            return null;
        }
        String n = normalize(s);
        for (ImpinjRfMode m : values()) {
            if (normalize(m.name()).equals(n)) {
                return m;
            }
        }
        return null;
    }

    /**
     * @return the named mode with this number, or null if it has no name here
     */
    public static ImpinjRfMode fromValue(int value) {
        for (ImpinjRfMode m : values()) {
            if (m.value == value) {
                return m;
            }
        }
        return null;
    }

    /**
     * The AutoSet modes are chosen by the reader's firmware and are usually
     * not listed in the reader's LLRP RF mode table.
     */
    public static boolean isAutoSet(int value) {
        return value >= AutoSetDenseReader.value && value <= AutoSetCustom.value;
    }

    /**
     * @return e.g. "AutoSetStaticFast (1003)", "1110" for a mode with no name,
     * or "reader default" for null
     */
    public static String describe(Integer value) {
        if (value == null) {
            return "reader default";
        }
        ImpinjRfMode m = fromValue(value);
        return m == null ? value.toString() : m.name() + " (" + value + ")";
    }

    static String normalize(String s) {
        return s.trim().replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
