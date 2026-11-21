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
package org.pikatimer.pikareader.conf;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.pikatimer.pikareader.readers.ReaderGatingStyle;
import org.pikatimer.pikareader.readers.impinj.ImpinjPowerLevels;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.tags.ReadFilterSettings;

/**
 * The basic settings the web UI's settings page can change: the unit's name,
 * timezone, chip format and web port, the reader region and gating, and the
 * list of readers (type, IP, power level and clock).
 *
 * Everything else in the config (profile overrides, antenna config, TLS, ...)
 * is left as it is. The changes are saved to the config file and take effect
 * when PikaReader restarts.
 *
 * The settings are a flat JSONObject keyed by the config file's own names:
 * UnitID, Timezone, ChipFormat, Port, Region, "Gating Style",
 * "Finish Mats Gating" ("" when not set) and Readers, a list of
 * {Index, Type, IP, "Power Level", Clock}.
 */
public final class BasicSettings {

    public static final String UNIT_ID = "UnitID";
    public static final String TIMEZONE = "Timezone";
    public static final String CHIP_FORMAT = "ChipFormat";
    public static final String PORT = "Port";
    public static final String REGION = "Region";
    public static final String GATING_STYLE = "Gating Style";
    public static final String FINISH_MATS_GATING = ReadFilterSettings.FINISH_MATS_GATING;
    public static final String READERS = "Readers";
    public static final String INDEX = "Index";
    public static final String TYPE = "Type";
    public static final String IP = "IP";
    public static final String POWER_LEVEL = "Power Level";
    public static final String CLOCK = "Clock";

    public static final List<String> CHIP_FORMATS = List.of("Decimal", "Hex");
    public static final List<String> CLOCKS = List.of("Local", "NTP");
    public static final String AUTO_REGION = "Auto";

    private static final int MAX_UNIT_ID = 64;
    private static final int MAX_HOST = 253;

    private BasicSettings() {
    }

    /** The region choices: Auto, then the region families. */
    public static List<String> regions() {
        List<String> r = new ArrayList<>();
        r.add(AUTO_REGION);
        r.addAll(ProfileManager.REGION_FAMILIES);
        return r;
    }

    public static List<String> gatingStyles() {
        return Arrays.stream(ReaderGatingStyle.values()).map(ReaderGatingStyle::getStyle).toList();
    }

    public static List<String> powerLevels() {
        return Arrays.stream(ImpinjPowerLevels.values()).map(ImpinjPowerLevels::getLevel).toList();
    }

    /** The current basic settings. */
    public static JSONObject read(PikaConfig config) {
        JSONObject root = new JSONObject();
        root.put(UNIT_ID, config.getStringValue(UNIT_ID));
        root.put(TIMEZONE, config.getStringValue(TIMEZONE));
        root.put(CHIP_FORMAT, config.getStringValue(CHIP_FORMAT));
        root.put("Web", config.getKey("Web"));
        root.put("Reader", config.getKey("Reader"));
        return read(root);
    }

    /**
     * The basic settings in a config, with the choices in their usual
     * spelling ("READER" reads as "Reader"). Values that aren't one of the
     * choices are passed through as they are, so the page can show them.
     */
    static JSONObject read(JSONObject root) {
        JSONObject web = root.optJSONObject("Web", new JSONObject());
        JSONObject reader = root.optJSONObject("Reader", new JSONObject());

        JSONObject s = new JSONObject();
        s.put(UNIT_ID, root.optString(UNIT_ID));
        s.put(TIMEZONE, root.optString(TIMEZONE, "AUTO"));
        s.put(CHIP_FORMAT, chipFormat(root.optString(CHIP_FORMAT)));
        s.put(PORT, web.opt(PORT) == null ? 8080 : web.opt(PORT));
        s.put(REGION, choice(reader.optString(REGION, AUTO_REGION), regions(), AUTO_REGION));
        s.put(GATING_STYLE, choice(reader.optString(GATING_STYLE), gatingStyles(), ReaderGatingStyle.READER.getStyle()));
        s.put(FINISH_MATS_GATING, reader.has(FINISH_MATS_GATING) ? reader.get(FINISH_MATS_GATING) : "");

        JSONArray readers = new JSONArray();
        JSONArray configured = reader.optJSONArray(READERS, new JSONArray());
        for (int i = 0; i < configured.length(); i++) {
            JSONObject rc = configured.optJSONObject(i);
            if (rc != null) {
                readers.put(readReader(rc));
            }
        }
        s.put(READERS, readers);
        return s;
    }

    private static JSONObject readReader(JSONObject rc) {
        JSONObject r = new JSONObject();
        r.put(INDEX, rc.opt(INDEX));
        r.put(TYPE, rc.optString(TYPE));
        r.put(IP, rc.optString(IP));
        r.put(POWER_LEVEL, choice(rc.optString(POWER_LEVEL), powerLevels(), ImpinjPowerLevels.HIGH.getLevel()));
        r.put(CLOCK, clock(rc.optString(CLOCK)));
        return r;
    }

    /**
     * Check settings sent by the settings page.
     *
     * @param in the new settings
     * @param current the settings now, from {@link #read}
     * @param readerTypes the reader types PikaReader has a driver for
     * @return what is wrong with them, empty if nothing is
     */
    public static List<String> validate(JSONObject in, JSONObject current, Set<String> readerTypes) {
        List<String> errors = new ArrayList<>();

        String unitId = in.optString(UNIT_ID).strip();
        if (unitId.isEmpty()) {
            errors.add("The Unit ID is required");
        } else if (unitId.chars().anyMatch(Character::isWhitespace)) {
            errors.add("The Unit ID can't contain spaces");
        } else if (unitId.length() > MAX_UNIT_ID) {
            errors.add("The Unit ID can't be longer than " + MAX_UNIT_ID + " characters");
        }

        String tz = in.optString(TIMEZONE).strip();
        try {
            PikaConfig.parseTimezone(tz);
        } catch (RuntimeException ex) {
            errors.add("Unknown timezone '" + tz + "'. Use AUTO, a zone such as America/Chicago, or an offset such as -06:00");
        }

        if (!CHIP_FORMATS.contains(in.optString(CHIP_FORMAT))) {
            errors.add("The chip format must be one of " + String.join(", ", CHIP_FORMATS));
        }

        Integer port = integer(in.opt(PORT));
        if (port == null || port < 1 || port > 65535) {
            errors.add("The web port must be a number from 1 to 65535");
        }

        if (!regions().contains(in.optString(REGION))) {
            errors.add("The region must be one of " + String.join(", ", regions()));
        }
        if (!gatingStyles().contains(in.optString(GATING_STYLE))) {
            errors.add("The gating style must be one of " + String.join(", ", gatingStyles()));
        }
        Object finish = in.opt(FINISH_MATS_GATING);
        if (!isBlank(finish)) {
            Double seconds = number(finish);
            if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds <= 0) {
                errors.add("The Finish Mats gating must be a number of seconds greater than 0, or blank for the default");
            }
        }

        JSONArray readers = in.optJSONArray(READERS);
        if (readers == null || readers.isEmpty()) {
            errors.add("Add at least one reader");
            return errors;
        }
        Map<Integer, JSONObject> currentReaders = byIndex(current.optJSONArray(READERS, new JSONArray()));
        Set<Integer> indexes = new HashSet<>();
        for (int i = 0; i < readers.length(); i++) {
            JSONObject r = readers.optJSONObject(i);
            if (r == null) {
                errors.add("Reader " + (i + 1) + " isn't a reader");
                continue;
            }
            Integer index = integer(r.opt(INDEX));
            String name = "Reader " + (index == null ? "#" + (i + 1) : index);
            if (index == null || index < 1) {
                errors.add(name + ": the Index must be a whole number of 1 or more");
            } else if (!indexes.add(index)) {
                errors.add(name + ": more than one reader uses Index " + index);
            }
            if (!readerTypes.contains(r.optString(TYPE))) {
                errors.add(name + ": the type must be one of " + String.join(", ", readerTypes.stream().sorted().toList()));
            }
            String ip = r.optString(IP).strip();
            if (ip.isEmpty()) {
                errors.add(name + ": the IP address is required");
            } else if (ip.chars().anyMatch(Character::isWhitespace) || ip.length() > MAX_HOST) {
                errors.add(name + ": '" + ip + "' isn't an IP address or hostname");
            }
            // A value set by hand in the file (e.g. a Zebra dBm) may be kept
            String power = r.optString(POWER_LEVEL);
            JSONObject was = index == null ? null : currentReaders.get(index);
            boolean unchanged = was != null && power.equals(was.optString(POWER_LEVEL));
            if (!powerLevels().contains(power) && !unchanged) {
                errors.add(name + ": the power level must be one of " + String.join(", ", powerLevels()));
            }
            if (!CLOCKS.contains(r.optString(CLOCK))) {
                errors.add(name + ": the clock must be one of " + String.join(", ", CLOCKS));
            }
        }
        return errors;
    }

    /**
     * Apply settings that passed {@link #validate} to the config. Only values
     * that changed are written, so the file keeps its own spelling of the
     * rest (e.g. "READER", or Clock = "Delta"). The "Reader" object is
     * changed in place, since other parts of PikaReader hold on to it; the
     * reader list is a new list of copies, so the running readers keep their
     * config until PikaReader restarts. Each reader keeps its other settings
     * (profile overrides, antenna config, TLS, ...).
     */
    public static void apply(JSONObject root, JSONObject in) {
        JSONObject current = read(root);

        putIfChanged(root, UNIT_ID, in.optString(UNIT_ID).strip(), current);
        putIfChanged(root, TIMEZONE, in.optString(TIMEZONE).strip(), current);
        putIfChanged(root, CHIP_FORMAT, in.optString(CHIP_FORMAT), current);

        JSONObject web = root.optJSONObject("Web");
        if (web == null) {
            web = new JSONObject();
            root.put("Web", web);
        }
        Integer port = integer(in.opt(PORT));
        if (!Objects.equals(port, integer(current.opt(PORT)))) {
            web.put(PORT, port);
        }

        JSONObject reader = root.optJSONObject("Reader");
        if (reader == null) {
            reader = new JSONObject();
            root.put("Reader", reader);
        }
        putIfChanged(reader, REGION, in.optString(REGION), current);
        putIfChanged(reader, GATING_STYLE, in.optString(GATING_STYLE), current);
        Object finish = in.opt(FINISH_MATS_GATING);
        if (isBlank(finish)) {
            reader.remove(FINISH_MATS_GATING);
        } else if (!Objects.equals(number(finish), number(current.opt(FINISH_MATS_GATING)))) {
            reader.put(FINISH_MATS_GATING, plainNumber(number(finish)));
        }

        Map<Integer, JSONObject> configured = byIndex(reader.optJSONArray(READERS, new JSONArray()));
        JSONArray readers = new JSONArray();
        JSONArray wanted = in.getJSONArray(READERS);
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject r = wanted.getJSONObject(i);
            Integer index = integer(r.opt(INDEX));
            JSONObject was = configured.get(index);
            JSONObject rc = was == null ? new JSONObject() : new JSONObject(was.toString());
            JSONObject wasBasic = was == null ? new JSONObject() : readReader(was);
            rc.put(INDEX, index);
            putIfChanged(rc, TYPE, r.optString(TYPE), wasBasic);
            putIfChanged(rc, IP, r.optString(IP).strip(), wasBasic);
            putIfChanged(rc, POWER_LEVEL, r.optString(POWER_LEVEL), wasBasic);
            putIfChanged(rc, CLOCK, r.optString(CLOCK), wasBasic);
            readers.put(rc);
        }
        reader.put(READERS, readers);
    }

    private static void putIfChanged(JSONObject target, String key, String value, JSONObject current) {
        if (!value.equals(current.optString(key, null))) {
            target.put(key, value);
        }
    }

    private static Map<Integer, JSONObject> byIndex(JSONArray readers) {
        Map<Integer, JSONObject> map = new HashMap<>();
        for (int i = 0; i < readers.length(); i++) {
            JSONObject r = readers.optJSONObject(i);
            Integer index = r == null ? null : integer(r.opt(INDEX));
            if (index != null) {
                map.putIfAbsent(index, r);
            }
        }
        return map;
    }

    /** The choice that matches s, ignoring case, or s itself if none does. */
    private static String choice(String s, List<String> choices, String blank) {
        if (s == null || s.isBlank()) {
            return blank;
        }
        return choices.stream().filter(c -> c.equalsIgnoreCase(s.strip())).findFirst().orElse(s);
    }

    // TagReadRouter treats anything starting with H as hex
    private static String chipFormat(String s) {
        return s != null && s.strip().toUpperCase().startsWith("H") ? "Hex" : "Decimal";
    }

    // Impinj and Zebra both treat anything other than NTP as the local clock
    private static String clock(String s) {
        return s != null && s.strip().equalsIgnoreCase("NTP") ? "NTP" : "Local";
    }

    private static boolean isBlank(Object o) {
        return o == null || JSONObject.NULL.equals(o) || (o instanceof String s && s.isBlank());
    }

    private static Double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.valueOf(s.strip());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** A whole number, from a number or a string; null if it isn't one. */
    static Integer integer(Object o) {
        Double d = number(o);
        if (d == null || d.isNaN() || d.isInfinite() || d != Math.rint(d)
                || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            return null;
        }
        return d.intValue();
    }

    // 3.0 is saved as 3, so the file reads Finish Mats Gating = 3
    private static Number plainNumber(Double d) {
        BigDecimal b = BigDecimal.valueOf(d).stripTrailingZeros();
        return b.scale() <= 0 && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (Number) d.intValue() : (Number) d;
    }
}
