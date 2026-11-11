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
package org.pikatimer.pikareader.readers.impinj;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public enum ImpinjPowerLevels {

    LOW("Low", 24.0), // ~0.25w
    MEDIUM("Medium", 27.0), // ~0.5w
    HIGH("High", 30.0), // ~1.0w
    MAX("Max", 32.5); // ~1.75w but it depends on the reader's max

    private final Double dBm;
    private final String level;

    private static final Map<String, ImpinjPowerLevels> levelMap = createMap();

    ImpinjPowerLevels(String s, Double l) {
        level = s;
        dBm = l;
    }

    private static Map<String, ImpinjPowerLevels> createMap() {
        Map<String, ImpinjPowerLevels> map = new HashMap<>();

        // Keyed by upper case name so lookups are case-insensitive
        for (ImpinjPowerLevels l : ImpinjPowerLevels.values()) {
            map.put(l.getLevel().toUpperCase(Locale.ROOT), l);
        }

        return Collections.unmodifiableMap(map);
    }

    // "Low", "LOW", "low", etc. all match. Unknown or missing values fall back to HIGH.
    public static ImpinjPowerLevels getLevel(String s){
        if (s == null) {
            return HIGH;
        }
        return levelMap.getOrDefault(s.trim().toUpperCase(Locale.ROOT), HIGH);
    }

    public String getLevel() {
        return level;
    }

    public Double getDBm() {
        return dBm;
    }

    @Override
    public String toString() {
        return this.level;
    }
}
