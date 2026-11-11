/*
 * Copyright (C) 2023 John Garner <segfaultcoredump@gmail.com>
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
package org.pikatimer.pikareader.readers;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public enum ReaderGatingStyle {

    BOX("Box"),
    ANTENNA("Antenna"),
    READER("Reader");

    private final String style;

    ReaderGatingStyle(String s) {
        style = s;
    }

    /**
     * @param s the style, in any case: "Box", "BOX" or "box"
     * @return the style, or READER if it isn't one
     */
    public static ReaderGatingStyle getStyle(String s){
        for (ReaderGatingStyle g : values()) {
            if (g.style.equalsIgnoreCase(s)) {
                return g;
            }
        }
        return READER;
    }

    public String getStyle() {
        return style;
    }

 

    @Override
    public String toString() {
        return this.style;
    }
}
