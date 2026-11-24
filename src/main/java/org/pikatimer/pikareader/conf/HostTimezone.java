/*
 * Copyright (C) 2026 John Garner <segfaultcoredump@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either VERSION 3 of the License, or
 * (at your option) any later VERSION.
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

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Makes a "Timezone" setting other than AUTO the JVM's default zone, so all
 * of PikaReader (log times included) runs as if the host were in that zone.
 * The OS zone is not changed.
 *
 * apply() runs first thing in main(), before anything logs: logback reads the
 * default zone once, when it starts. So this class must not use SLF4J or any
 * class that has a static logger (such as PikaConfig). Problems are kept for
 * PikaConfig to log; it reports a bad config file or zone itself anyway.
 */
public final class HostTimezone {

    /** A "Timezone" of whole hours, such as "-6" or "+10" */
    private static final Pattern OFFSET_HOURS = Pattern.compile("[+-]\\d+");

    /** The host's zone, from before apply() changed the default */
    private static volatile ZoneId hostZone = null;

    /** The zone apply() made the default, or null if it left it alone */
    private static volatile ZoneId override = null;

    /** Why apply() left the default alone, or null */
    private static volatile String problem = null;

    private HostTimezone() {
    }

    /** The config file: -DCONFIG, else ~/.PikaReader.toml */
    public static Path configPath() {
        return Path.of(System.getProperty("CONFIG", System.getProperty("user.home") + "/.PikaReader.toml"));
    }

    /**
     * The zone for a "Timezone" setting: "AUTO" (or blank) for the system
     * timezone, an offset such as "-6" or "-06:00", or a zone ID such as
     * "America/Chicago".
     *
     * @throws java.time.DateTimeException if it is none of those
     */
    public static ZoneId parse(String timezone) {
        String tz = timezone == null ? "" : timezone.strip();
        return switch (tz.toUpperCase()) {
            case "", "AUTO" ->
                ZoneId.systemDefault();
            case String s when OFFSET_HOURS.matcher(s).matches() ->
                ZoneId.ofOffset("UTC", ZoneOffset.of(tz));
            default ->
                ZoneId.of(tz);
        };
    }

    /** True for "AUTO" or blank: use the host's zone. */
    static boolean isAuto(String timezone) {
        String tz = timezone == null ? "" : timezone.strip();
        return tz.isEmpty() || tz.equalsIgnoreCase("AUTO");
    }

    /** Read the config file's Timezone and, unless it is AUTO, make it the default zone. */
    public static void apply() {
        apply(configPath());
    }

    static synchronized void apply(Path config) {
        hostZone = ZoneId.systemDefault();
        override = null;
        problem = null;
        if (!Files.isRegularFile(config)) {
            return; // first run: the template says AUTO
        }
        String tz;
        try (Reader r = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            CommentedConfig doc = TomlJson.parse(r);
            Object value = doc.get("Timezone");
            tz = value == null ? "" : value.toString();
        } catch (Exception ex) {
            problem = "unable to read " + config + ": " + ex.getMessage();
            return;
        }
        if (isAuto(tz)) {
            return;
        }
        ZoneId zone;
        try {
            zone = parse(tz);
        } catch (RuntimeException ex) {
            problem = "unknown timezone '" + tz.strip() + "'";
            return;
        }
        TimeZone.setDefault(toTimeZone(zone));
        override = zone;
    }

    /**
     * The TimeZone for a zone. TimeZone doesn't know "UTC-06:00" style IDs
     * (it would quietly use GMT), so fixed offsets go over as "-06:00".
     */
    static TimeZone toTimeZone(ZoneId zone) {
        return TimeZone.getTimeZone(zone.getRules().isFixedOffset() ? zone.normalized() : zone);
    }

    /**
     * The host's own zone, from before apply() changed the default. The
     * system default if apply() hasn't run (e.g. in tests).
     */
    public static ZoneId hostZone() {
        ZoneId z = hostZone;
        return z == null ? ZoneId.systemDefault() : z;
    }

    /** The zone apply() made the default, or null if it didn't change it. */
    public static ZoneId overrideZone() {
        return override;
    }

    /** What apply() did, for the log. */
    public static String summary() {
        if (override != null) {
            return "host zone " + hostZone() + " overridden by " + override + " for PikaReader";
        }
        if (problem != null) {
            return "host zone " + hostZone() + " kept: " + problem;
        }
        return "using the host zone " + hostZone();
    }
}
