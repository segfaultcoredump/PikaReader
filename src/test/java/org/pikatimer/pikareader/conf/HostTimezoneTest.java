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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HostTimezone.apply(): a Timezone other than AUTO becomes the JVM default.
 */
class HostTimezoneTest {

    @TempDir
    Path dir;

    private TimeZone saved;

    // A host zone that none of the tests set, so a change always shows
    private static final ZoneId HOST = ZoneId.of("Pacific/Auckland");

    @BeforeEach
    void setUp() {
        saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(HOST));
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(saved);
    }

    private Path config(String timezoneLine) throws IOException {
        Path f = dir.resolve("PikaReader.toml");
        Files.writeString(f, "Version = \"1.0\"\nUnitID = \"test\"\n" + timezoneLine + "\nChipFormat = \"Decimal\"\n");
        return f;
    }

    private static ZoneOffset defaultOffset() {
        return ZoneId.systemDefault().getRules().getOffset(Instant.parse("2026-06-15T12:00:00Z"));
    }

    @Test
    void aZoneIdBecomesTheDefault() throws IOException {
        HostTimezone.apply(config("Timezone = \"America/Chicago\""));
        assertEquals(ZoneId.of("America/Chicago"), ZoneId.systemDefault());
        assertEquals(ZoneId.of("America/Chicago"), HostTimezone.overrideZone());
        assertEquals(HOST, HostTimezone.hostZone());
        assertTrue(HostTimezone.summary().contains("overridden"), HostTimezone.summary());
    }

    @Test
    void offsetsBecomeTheDefaultInsteadOfGmt() throws IOException {
        for (String tz : new String[]{"\"-6\"", "\"-06:00\"", "-6"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(HOST));
            HostTimezone.apply(config("Timezone = " + tz));
            assertEquals(ZoneOffset.ofHours(-6), defaultOffset(), tz);
            assertEquals(HOST, HostTimezone.hostZone(), tz);
        }
    }

    @Test
    void autoLeavesTheHostZone() throws IOException {
        for (String tz : new String[]{"Timezone = \"AUTO\"", "Timezone = \"auto\"", "Timezone = \"\"", ""}) {
            HostTimezone.apply(config(tz));
            assertEquals(HOST, ZoneId.systemDefault(), tz);
            assertNull(HostTimezone.overrideZone(), tz);
            assertEquals(HOST, HostTimezone.hostZone(), tz);
        }
    }

    @Test
    void problemsLeaveTheHostZone() throws IOException {
        HostTimezone.apply(config("Timezone = \"Mars/Olympus\""));
        assertEquals(HOST, ZoneId.systemDefault());
        assertTrue(HostTimezone.summary().contains("Mars/Olympus"), HostTimezone.summary());

        HostTimezone.apply(config("Timezone = \"America/Chicago"));
        assertEquals(HOST, ZoneId.systemDefault());
        assertTrue(HostTimezone.summary().contains("unable to read"), HostTimezone.summary());

        HostTimezone.apply(dir.resolve("missing.toml"));
        assertEquals(HOST, ZoneId.systemDefault());
        assertNull(HostTimezone.overrideZone());
    }

    @Test
    void parseAcceptsTheSettingForms() {
        assertEquals(ZoneId.systemDefault(), HostTimezone.parse("AUTO"));
        assertEquals(ZoneId.systemDefault(), HostTimezone.parse(" "));
        assertEquals(ZoneId.of("America/Chicago"), HostTimezone.parse("America/Chicago"));
        assertEquals(ZoneOffset.ofHours(10), HostTimezone.parse("+10").normalized());
        assertThrows(RuntimeException.class, () -> HostTimezone.parse("Mars/Olympus"));
    }
}
