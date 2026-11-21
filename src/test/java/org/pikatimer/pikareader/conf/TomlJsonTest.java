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
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the TOML config converts to the in-memory JSONObject and back
 * without losing the comments or the key order.
 */
class TomlJsonTest {

    private static final String SAMPLE = """
            # PikaReader config
            Version = "1.0"
            # The unit's name
            UnitID = "26:D8:16"
            Timezone = "AUTO"

            [Web]
            # HTTP port
            Port = 8080

            [Reader]
            # The reader profile
            Profile = "finish_mats"
            "Gating Style" = "Reader"
            Reading = false

            # The first reader
            [[Reader.Readers]]
            Index = 2
            Type = "IMPINJ"
            # Reader IP
            IP = "192.168.1.131"
            "Power Level" = "Low"
            Ratio = 1.5

            [Reader.Readers."Antenna Config"]
            4 = "Disabled"

            [Reader.Readers.Profiles.finish_mats]
            Session = 3

            [[Reader.Readers]]
            Index = 1
            Type = "ZEBRA"
            IP = "192.168.1.141"
            """;

    private static String write(CommentedConfig doc) {
        StringWriter w = new StringWriter();
        try {
            TomlJson.write(doc, w);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return w.toString();
    }

    @Test
    void parsesToTheSameJsonOrgJsonWouldHave() {
        JSONObject json = TomlJson.toJson(TomlJson.parse(new StringReader(SAMPLE)));

        assertEquals("26:D8:16", json.getString("UnitID"));
        assertEquals(8080, json.getJSONObject("Web").get("Port"));
        assertInstanceOf(Integer.class, json.getJSONObject("Web").get("Port"));
        JSONObject reader = json.getJSONObject("Reader");
        assertEquals(false, reader.get("Reading"));
        assertEquals("Reader", reader.getString("Gating Style"));
        JSONArray readers = reader.getJSONArray("Readers");
        assertEquals(2, readers.length());
        JSONObject first = readers.getJSONObject(0);
        assertEquals(2, first.getInt("Index"));
        assertEquals("Low", first.getString("Power Level"));
        assertEquals(1.5, first.getDouble("Ratio"));
        assertEquals("Disabled", first.getJSONObject("Antenna Config").getString("4"));
        assertEquals(3, first.getJSONObject("Profiles").getJSONObject("finish_mats").getInt("Session"));
        assertEquals("ZEBRA", readers.getJSONObject(1).getString("Type"));
    }

    @Test
    void mergeKeepsCommentsAndOrder() {
        CommentedConfig doc = TomlJson.parse(new StringReader(SAMPLE));
        JSONObject json = TomlJson.toJson(doc);

        // The kinds of changes PikaReader makes on its own
        json.put("UnitIP", "10.0.0.5");
        json.remove("Timezone");
        JSONObject reader = json.getJSONObject("Reader");
        reader.put("Reading", true);
        reader.put("Region", "Auto");
        JSONObject newReader = new JSONObject();
        newReader.put("Index", 3);
        newReader.put("Type", "IMPINJ");
        reader.getJSONArray("Readers").put(newReader);
        json.put("DB", new JSONObject().put("Path", "/tmp/db").put("Retention", 180));

        TomlJson.merge(doc, json);
        String out = write(doc);

        // Comments survive, including ones inside an array of tables
        for (String comment : new String[]{"# PikaReader config", "# The unit's name", "# HTTP port",
            "# The reader profile", "# The first reader", "# Reader IP"}) {
            assertTrue(out.contains(comment), () -> "Lost '" + comment + "' in:\n" + out);
        }
        // Existing keys stay where they were, new ones go at the end
        assertTrue(out.indexOf("Version") < out.indexOf("UnitID"), out);
        assertTrue(out.indexOf("UnitID") < out.indexOf("UnitIP"), out);
        assertTrue(out.indexOf("Profile =") < out.indexOf("Reading"), out);
        assertTrue(out.indexOf("Reading") < out.indexOf("Region"), out);
        assertFalse(out.contains("Timezone"), out);
        // Readable sections, not inline tables
        assertTrue(out.contains("[[Reader.Readers]]"), out);
        assertTrue(out.contains("[DB]"), out);

        // And it reads back as the same config
        assertTrue(json.similar(TomlJson.toJson(TomlJson.parse(new StringReader(out)))), out);
    }

    @Test
    void defaultTemplateParses() throws Exception {
        try (var in = PikaConfig.class.getResourceAsStream("/default-config.toml")) {
            assertNotNull(in, "default-config.toml is missing");
            CommentedConfig doc = TomlJson.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            JSONObject json = TomlJson.toJson(doc);
            JSONObject reader = json.getJSONObject("Reader");
            // Runtime state lives in the state file, not the config
            assertFalse(reader.has("Profile"));
            assertFalse(reader.has("Reading"));
            assertFalse(json.has("UnitIP"));
            assertEquals("", json.getString("UnitID"));
            assertEquals("Auto", reader.getString("Region"));
            JSONObject first = reader.getJSONArray("Readers").getJSONObject(0);
            assertEquals(1, first.getInt("Index"));
            assertEquals("IMPINJ", first.getString("Type"));
            assertEquals(8080, json.getJSONObject("Web").getInt("Port"));
            assertEquals(180, json.getJSONObject("DB").getInt("Retention"));

            // A save keeps the template's comments
            TomlJson.merge(doc, json.put("UnitID", "pr4-01"));
            String out = write(doc);
            assertTrue(out.contains("# The name this unit reports"), out);
            assertTrue(out.contains("UnitID = \"pr4-01\""), out);
        }
    }
}
