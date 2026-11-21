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

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The state file (profile, reading) and the UnitID hostname default.
 */
class PikaStateTest {

    @TempDir
    Path dir;

    @Test
    void missingFileGivesTheDefaults() {
        PikaState state = new PikaState(dir.resolve(PikaConfig.STATE_FILE));
        assertEquals("finish_mats", state.getString("Profile", "finish_mats"));
        assertFalse(state.getBoolean("Reading", false));
        assertFalse(Files.exists(dir.resolve(PikaConfig.STATE_FILE)), "nothing changed, so nothing is written");
    }

    @Test
    void putWritesAFileThatReadsBack() throws Exception {
        Path file = dir.resolve(PikaConfig.STATE_FILE);
        PikaState state = new PikaState(file);
        state.put("Profile", "start_mats");
        state.put("Reading", true);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("# PikaReader's saved state"), text);
        JSONObject json = TomlJson.toJson(TomlJson.parse(new StringReader(text)));
        assertEquals("start_mats", json.getString("Profile"));
        assertTrue(json.getBoolean("Reading"));
        assertFalse(Files.exists(dir.resolve(PikaConfig.STATE_FILE + ".tmp")));

        // A new instance (a restart) sees the saved values
        PikaState restarted = new PikaState(file);
        assertEquals("start_mats", restarted.getString("Profile", "finish_mats"));
        assertTrue(restarted.getBoolean("Reading", false));
    }

    @Test
    void unchangedValuesDontRewriteTheFile() throws Exception {
        Path file = dir.resolve(PikaConfig.STATE_FILE);
        PikaState state = new PikaState(file);
        state.put("Reading", false);
        FileTime old = FileTime.fromMillis(0);
        Files.setLastModifiedTime(file, old);

        state.put("Reading", false);
        assertEquals(old, Files.getLastModifiedTime(file));

        state.put("Reading", true);
        assertNotEquals(old, Files.getLastModifiedTime(file));
    }

    @Test
    void damagedFileGivesTheDefaults() throws Exception {
        Path file = dir.resolve(PikaConfig.STATE_FILE);
        Files.writeString(file, "Profile = \"start_m", StandardCharsets.UTF_8);
        PikaState state = new PikaState(file);
        assertEquals("finish_mats", state.getString("Profile", "finish_mats"));
        assertFalse(state.getBoolean("Reading", false));

        // The next change replaces it with a good file
        state.put("Reading", true);
        assertTrue(new PikaState(file).getBoolean("Reading", false));
    }

    @Test
    void unitIdIsTheHostnameWithoutTheDomain() {
        assertEquals("pr4-01", PikaConfig.unqualified("pr4-01.local"));
        assertEquals("pr4-01", PikaConfig.unqualified("pr4-01"));
        assertEquals("pr4-01", PikaConfig.unqualified(" pr4-01.lan.example.org\n"));
        assertEquals("", PikaConfig.unqualified(""));
        assertEquals("", PikaConfig.unqualified(null));
        assertEquals("", PikaConfig.unqualified(".local"));

        String host = PikaConfig.hostname();
        assertFalse(host.isBlank());
        assertFalse(host.contains("."), host);
    }
}
