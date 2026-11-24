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

import com.electronwill.nightconfig.core.CommentedConfig;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the settings page's reading, checking and saving of the [Remote]
 * (PikaRelay) settings.
 */
class RemoteSettingsTest {

    private static final String CONFIG = """
            Version = "1.0"
            UnitID = "pr4-01"

            # Send to the relay
            [Remote]
            Enabled = true
            # The relay
            URL = "https://relay.example.com"
            Key = "k1"
            Secret = "s3cret"
            """;

    private static JSONObject config() {
        return TomlJson.toJson(TomlJson.parse(new StringReader(CONFIG)));
    }

    private static JSONObject in(boolean enabled, String url, String key, String secret) {
        return new JSONObject().put("Enabled", enabled).put("URL", url).put("Key", key).put("Secret", secret);
    }

    @Test
    void readNeverReturnsTheSecret() {
        JSONObject s = RemoteSettings.read(config());
        assertTrue(s.getBoolean("Enabled"));
        assertEquals("https://relay.example.com", s.getString("URL"));
        assertEquals("k1", s.getString("Key"));
        assertTrue(s.getBoolean("SecretSet"));
        assertFalse(s.has("Secret"));
        assertFalse(s.toString().contains("s3cret"));
    }

    @Test
    void aMissingSectionIsOff() {
        JSONObject s = RemoteSettings.read(new JSONObject());
        assertFalse(s.getBoolean("Enabled"));
        assertEquals("", s.getString("URL"));
        assertFalse(s.getBoolean("SecretSet"));
        assertFalse(RemoteSettings.isEnabled(new JSONObject()));
    }

    @Test
    void validation() {
        JSONObject current = RemoteSettings.read(config());
        JSONObject none = RemoteSettings.read(new JSONObject());
        assertEquals(List.of(), RemoteSettings.validate(in(true, "https://relay.example.com/relay/index.php", "k1", ""), current, "pr4-01"));
        assertEquals(List.of(), RemoteSettings.validate(in(false, "", "", ""), none, "pr4-01"));
        assertEquals(List.of(), RemoteSettings.validate(in(true, "http://127.0.0.1:8090", "", ""), none, "pr4-01"));

        assertFalse(RemoteSettings.validate(in(true, "", "", ""), none, "pr4-01").isEmpty());
        assertFalse(RemoteSettings.validate(in(true, "ftp://relay.example.com", "", ""), none, "pr4-01").isEmpty());
        assertFalse(RemoteSettings.validate(in(true, "relay.example.com", "", ""), none, "pr4-01").isEmpty());
        // A key needs a secret, unless one is saved already
        assertFalse(RemoteSettings.validate(in(true, "https://r.example.com", "k1", ""), none, "pr4-01").isEmpty());
        assertFalse(RemoteSettings.validate(in(true, "https://r.example.com", "", "s"), none, "pr4-01").isEmpty());
        assertFalse(RemoteSettings.validate(in(true, "https://r.example.com", "k 1", "s"), none, "pr4-01").isEmpty());
        // The UnitID must be a valid relay reader id, but only when enabled
        assertFalse(RemoteSettings.validate(in(true, "https://r.example.com", "", ""), none, "Finish!").isEmpty());
        assertFalse(RemoteSettings.validate(in(true, "https://r.example.com", "", ""), none, "-pr4").isEmpty());
        assertEquals(List.of(), RemoteSettings.validate(in(false, "https://r.example.com", "", ""), none, "Finish!"));
        assertFalse(RemoteSettings.validate(new JSONObject().put("Enabled", "yes"), none, "pr4-01").isEmpty());
    }

    @Test
    void basicSettingsRequireARelayUnitIdOnlyWhenSending() {
        JSONObject root = TomlJson.toJson(TomlJson.parse(new StringReader("""
                UnitID = "pr4-01"
                Timezone = "AUTO"
                ChipFormat = "Decimal"
                [Reader]
                [[Reader.Readers]]
                Index = 1
                Type = "IMPINJ"
                IP = "10.0.0.1"
                """)));
        JSONObject s = BasicSettings.read(root);
        s.put("UnitID", "pr4~01");
        assertEquals(List.of(), BasicSettings.validate(s, BasicSettings.read(root), Set.of("IMPINJ"), false));
        assertEquals(List.of(RemoteSettings.RELAY_ID_RULE), BasicSettings.validate(s, BasicSettings.read(root), Set.of("IMPINJ"), true));
    }

    @Test
    void aBlankSecretKeepsTheSavedOne() {
        JSONObject root = config();
        JSONObject live = root.getJSONObject("Remote");
        RemoteSettings.apply(root, in(false, "https://other.example.com/", "k2", ""));
        assertSame(live, root.getJSONObject("Remote"));
        assertFalse(live.getBoolean("Enabled"));
        assertEquals("https://other.example.com/", live.getString("URL"));
        assertEquals("k2", live.getString("Key"));
        assertEquals("s3cret", live.getString("Secret"));

        RemoteSettings.apply(root, in(false, "https://other.example.com/", "k2", "new"));
        assertEquals("new", live.getString("Secret"));
    }

    @Test
    void aBlankKeyClearsTheKeyAndSecret() {
        JSONObject root = config();
        RemoteSettings.apply(root, in(true, "https://relay.example.com", "", ""));
        assertEquals("", root.getJSONObject("Remote").getString("Key"));
        assertEquals("", root.getJSONObject("Remote").getString("Secret"));
        assertFalse(RemoteSettings.read(root).getBoolean("SecretSet"));
    }

    @Test
    void savingUnchangedSettingsChangesNothing() {
        JSONObject root = config();
        JSONObject before = new JSONObject(root.toString());
        JSONObject s = RemoteSettings.read(root);
        RemoteSettings.apply(root, in(s.getBoolean("Enabled"), s.getString("URL"), s.getString("Key"), ""));
        assertTrue(before.similar(root), root.toString(2));
    }

    @Test
    void theFileKeepsItsCommentsAndGainsTheSection() throws Exception {
        CommentedConfig doc = TomlJson.parse(new StringReader(CONFIG));
        JSONObject root = TomlJson.toJson(doc);
        RemoteSettings.apply(root, in(false, "https://relay.example.com", "k1", ""));
        TomlJson.merge(doc, root);
        StringWriter out = new StringWriter();
        TomlJson.write(doc, out);
        String toml = out.toString();
        assertTrue(toml.contains("# Send to the relay"), toml);
        assertTrue(toml.contains("# The relay"), toml);
        assertTrue(toml.contains("Enabled = false"), toml);

        // A config without [Remote] gets one
        CommentedConfig bare = TomlJson.parse(new StringReader("UnitID = \"pr4-01\"\n"));
        JSONObject bareRoot = TomlJson.toJson(bare);
        RemoteSettings.apply(bareRoot, in(true, "https://relay.example.com", "", ""));
        TomlJson.merge(bare, bareRoot);
        StringWriter out2 = new StringWriter();
        TomlJson.write(bare, out2);
        JSONObject reread = TomlJson.toJson(TomlJson.parse(new StringReader(out2.toString())));
        assertTrue(reread.getJSONObject("Remote").getBoolean("Enabled"), out2.toString());
        assertEquals("https://relay.example.com", reread.getJSONObject("Remote").getString("URL"));
    }
}
