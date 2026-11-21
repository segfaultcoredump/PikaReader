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

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the settings page's reading, checking and saving of the basic
 * settings, and that it leaves the advanced reader settings alone.
 */
class BasicSettingsTest {

    private static final Set<String> TYPES = Set.of("IMPINJ", "ZEBRA");

    private static final String CONFIG = """
            Version = "1.0"
            UnitID = "pr4-01"
            Timezone = "AUTO"
            ChipFormat = "Decimal"

            [Web]
            Port = 8080

            [Reader]
            Region = "Auto"
            "Gating Style" = "READER"
            "Finish Mats Gating" = 3

            [[Reader.Readers]]
            Index = 1
            Type = "IMPINJ"
            IP = "192.168.1.131"
            "Power Level" = "High"
            TLS = true

            [Reader.Readers."Antenna Config"]
            4 = "Disabled"

            [Reader.Readers.Profiles.finish_mats]
            Session = 3

            [[Reader.Readers]]
            Index = 2
            Type = "ZEBRA"
            IP = "192.168.1.141"
            "Power Level" = 28.5
            Clock = "Delta"
            """;

    private static JSONObject config() {
        return TomlJson.toJson(TomlJson.parse(new StringReader(CONFIG)));
    }

    /** The current settings, changed by the test. */
    private static JSONObject edited(JSONObject root, Consumer<JSONObject> change) {
        JSONObject s = BasicSettings.read(root);
        change.accept(s);
        return s;
    }

    private static List<String> errors(Consumer<JSONObject> change) {
        JSONObject root = config();
        return BasicSettings.validate(edited(root, change), BasicSettings.read(root), TYPES);
    }

    @Test
    void readsTheBasicSettingsInTheirUsualSpelling() {
        JSONObject s = BasicSettings.read(config());
        assertEquals("pr4-01", s.getString("UnitID"));
        assertEquals(8080, s.getInt("Port"));
        assertEquals("Reader", s.getString("Gating Style"));
        assertEquals(3, s.getInt("Finish Mats Gating"));
        JSONArray readers = s.getJSONArray("Readers");
        assertEquals(2, readers.length());
        assertEquals("Local", readers.getJSONObject(0).getString("Clock"));
        assertFalse(readers.getJSONObject(0).has("TLS"));
        assertEquals("28.5", readers.getJSONObject(1).getString("Power Level"));
        assertEquals("Local", readers.getJSONObject(1).getString("Clock"));
    }

    @Test
    void theCurrentSettingsAreValid() {
        assertEquals(List.of(), errors(s -> {
        }));
    }

    @Test
    void savingUnchangedSettingsChangesNothing() {
        JSONObject root = config();
        JSONObject before = new JSONObject(root.toString());
        BasicSettings.apply(root, BasicSettings.read(root));
        assertTrue(before.similar(root), root.toString(2));
    }

    @Test
    void applyKeepsTheAdvancedSettingsAndTheLiveReaderObject() {
        JSONObject root = config();
        JSONObject liveReader = root.getJSONObject("Reader");
        JSONObject runningReader = liveReader.getJSONArray("Readers").getJSONObject(0);

        JSONObject s = edited(root, in -> {
            in.put("UnitID", "finish-1");
            in.put("Port", 8081);
            in.put("Gating Style", "Box");
            in.put("Finish Mats Gating", "");
            JSONArray readers = in.getJSONArray("Readers");
            readers.getJSONObject(0).put("IP", "10.0.0.5").put("Power Level", "Max").put("Clock", "NTP");
            readers.remove(1);
            readers.put(new JSONObject().put("Index", 3).put("Type", "ZEBRA").put("IP", "10.0.0.6")
                    .put("Power Level", "Low").put("Clock", "Local"));
        });
        assertEquals(List.of(), BasicSettings.validate(s, BasicSettings.read(root), TYPES));
        BasicSettings.apply(root, s);

        assertEquals("finish-1", root.getString("UnitID"));
        assertEquals(8081, root.getJSONObject("Web").getInt("Port"));
        assertSame(liveReader, root.getJSONObject("Reader"));
        assertEquals("Box", liveReader.getString("Gating Style"));
        assertFalse(liveReader.has("Finish Mats Gating"));

        JSONArray readers = liveReader.getJSONArray("Readers");
        assertEquals(2, readers.length());
        JSONObject first = readers.getJSONObject(0);
        assertEquals("10.0.0.5", first.getString("IP"));
        assertEquals("Max", first.getString("Power Level"));
        assertEquals("NTP", first.getString("Clock"));
        assertTrue(first.getBoolean("TLS"));
        assertEquals("Disabled", first.getJSONObject("Antenna Config").getString("4"));
        assertEquals(3, first.getJSONObject("Profiles").getJSONObject("finish_mats").getInt("Session"));
        JSONObject added = readers.getJSONObject(1);
        assertEquals(3, added.getInt("Index"));
        assertEquals("ZEBRA", added.getString("Type"));

        // The running reader's own config is untouched until the restart
        assertEquals("192.168.1.131", runningReader.getString("IP"));
        assertNotSame(runningReader, first);
    }

    @Test
    void unchangedValuesKeepTheFilesSpelling() {
        JSONObject root = config();
        JSONObject s = edited(root, in -> in.getJSONArray("Readers").getJSONObject(0).put("IP", "10.0.0.5"));
        BasicSettings.apply(root, s);
        JSONObject reader = root.getJSONObject("Reader");
        assertEquals("READER", reader.getString("Gating Style"));
        JSONObject zebra = reader.getJSONArray("Readers").getJSONObject(1);
        assertEquals("Delta", zebra.getString("Clock"));
        assertEquals(28.5, zebra.getDouble("Power Level"));
        assertFalse(reader.getJSONArray("Readers").getJSONObject(0).has("Clock"));
    }

    @Test
    void finishMatsGatingIsSavedAsANumber() {
        JSONObject root = config();
        BasicSettings.apply(root, edited(root, in -> in.put("Finish Mats Gating", "2.5")));
        assertEquals(2.5, root.getJSONObject("Reader").getDouble("Finish Mats Gating"));
        BasicSettings.apply(root, edited(root, in -> in.put("Finish Mats Gating", 4.0)));
        assertEquals(4, root.getJSONObject("Reader").get("Finish Mats Gating"));
    }

    @Test
    void acceptsAnUnchangedHandSetPowerLevel() {
        assertEquals(List.of(), errors(s -> s.getJSONArray("Readers").getJSONObject(1).put("IP", "10.0.0.7")));
        assertEquals(1, errors(s -> s.getJSONArray("Readers").getJSONObject(1).put("Power Level", "29")).size());
    }

    @Test
    void rejectsBadSettings() {
        assertFalse(errors(s -> s.put("UnitID", "")).isEmpty());
        assertFalse(errors(s -> s.put("UnitID", "finish line")).isEmpty());
        assertFalse(errors(s -> s.put("Timezone", "Mars/Olympus")).isEmpty());
        assertFalse(errors(s -> s.put("Port", 0)).isEmpty());
        assertFalse(errors(s -> s.put("Port", 70000)).isEmpty());
        assertFalse(errors(s -> s.put("Port", "abc")).isEmpty());
        assertFalse(errors(s -> s.put("ChipFormat", "Octal")).isEmpty());
        assertFalse(errors(s -> s.put("Region", "mars")).isEmpty());
        assertFalse(errors(s -> s.put("Gating Style", "Foo")).isEmpty());
        assertFalse(errors(s -> s.put("Finish Mats Gating", -1)).isEmpty());
        assertFalse(errors(s -> s.put("Readers", new JSONArray())).isEmpty());
        assertFalse(errors(s -> s.getJSONArray("Readers").getJSONObject(1).put("Index", 1)).isEmpty());
        assertFalse(errors(s -> s.getJSONArray("Readers").getJSONObject(0).put("Type", "LLRP")).isEmpty());
        assertFalse(errors(s -> s.getJSONArray("Readers").getJSONObject(0).put("IP", " ")).isEmpty());
        assertFalse(errors(s -> s.getJSONArray("Readers").getJSONObject(0).put("Clock", "GPS")).isEmpty());
    }

    @Test
    void acceptsTimezoneForms() {
        for (String tz : List.of("AUTO", "auto", "America/Chicago", "-06:00", "+10", "UTC")) {
            assertEquals(List.of(), errors(s -> s.put("Timezone", tz)), tz);
        }
    }
}
