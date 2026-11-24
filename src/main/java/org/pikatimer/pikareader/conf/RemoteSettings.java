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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * The [Remote] section of the config: sending reads and status to a
 * PikaRelay server and taking commands from it.
 *
 * Unlike the basic settings these take effect right away (RelayClient reads
 * the live "Remote" object on every cycle) and can be changed while reading.
 * The secret is write-only: read() only says whether one is set.
 */
public final class RemoteSettings {

    public static final String REMOTE = "Remote";
    public static final String ENABLED = "Enabled";
    public static final String URL = "URL";
    public static final String KEY = "Key";
    public static final String SECRET = "Secret";
    public static final String SECRET_SET = "SecretSet";

    /** PikaRelay's rule for reader ids. The UnitID is the reader id. */
    public static final Pattern RELAY_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public static final String RELAY_ID_RULE = "The Unit ID must start with a letter or digit and use only letters, digits, '.', '_' and '-' to send to PikaRelay";

    private static final int MAX_URL = 2048;
    private static final int MAX_CREDENTIAL = 256;

    private RemoteSettings() {
    }

    /** The remote settings for the settings page, without the secret. */
    public static JSONObject read(PikaConfig config) {
        JSONObject root = new JSONObject();
        root.put(REMOTE, config.getKey(REMOTE));
        return read(root);
    }

    static JSONObject read(JSONObject root) {
        JSONObject remote = root.optJSONObject(REMOTE, new JSONObject());
        JSONObject s = new JSONObject();
        s.put(ENABLED, remote.optBoolean(ENABLED, false));
        s.put(URL, remote.optString(URL));
        s.put(KEY, remote.optString(KEY));
        s.put(SECRET_SET, !remote.optString(SECRET).isEmpty());
        return s;
    }

    /** True if sending to PikaRelay is turned on in this config. */
    public static boolean isEnabled(JSONObject root) {
        JSONObject remote = root.optJSONObject(REMOTE);
        return remote != null && remote.optBoolean(ENABLED, false);
    }

    /**
     * Check remote settings sent by the settings page.
     *
     * @param in the new settings: Enabled, URL, Key and Secret (blank keeps
     * the saved secret)
     * @param current the settings now, from {@link #read}
     * @param unitId the unit's UnitID, which is its id on the relay
     * @return what is wrong with them, empty if nothing is
     */
    public static List<String> validate(JSONObject in, JSONObject current, String unitId) {
        List<String> errors = new ArrayList<>();
        Object enabled = in.opt(ENABLED);
        if (enabled != null && !(enabled instanceof Boolean)) {
            errors.add("Enabled must be true or false");
        }
        boolean on = Boolean.TRUE.equals(enabled);

        String url = in.optString(URL).strip();
        if (url.isEmpty()) {
            if (on) {
                errors.add("The PikaRelay URL is required to send to PikaRelay");
            }
        } else if (url.length() > MAX_URL || !isHttpUrl(url)) {
            errors.add("The PikaRelay URL must be an http:// or https:// address, such as https://relay.example.com");
        }

        String key = in.optString(KEY).strip();
        String secret = in.optString(SECRET).strip();
        if (!validCredential(key)) {
            errors.add("The API key can't contain spaces or be longer than " + MAX_CREDENTIAL + " characters");
        }
        if (!validCredential(secret)) {
            errors.add("The API secret can't contain spaces or be longer than " + MAX_CREDENTIAL + " characters");
        }
        if (!key.isEmpty() && secret.isEmpty() && !current.optBoolean(SECRET_SET, false)) {
            errors.add("Enter the API secret for the key");
        }
        if (key.isEmpty() && !secret.isEmpty()) {
            errors.add("Enter the API key for the secret");
        }

        if (on && (unitId == null || !RELAY_ID.matcher(unitId).matches())) {
            errors.add(RELAY_ID_RULE + " (it is '" + unitId + "')");
        }
        return errors;
    }

    /**
     * Apply settings that passed {@link #validate}. Only changed values are
     * written. The "Remote" object is changed in place if there is one, since
     * RelayClient holds on to it. A blank secret keeps the saved one; a blank
     * key removes the key and the secret.
     */
    public static void apply(JSONObject root, JSONObject in) {
        JSONObject remote = root.optJSONObject(REMOTE);
        if (remote == null) {
            remote = new JSONObject();
            root.put(REMOTE, remote);
        }
        boolean enabled = in.optBoolean(ENABLED, false);
        if (!remote.has(ENABLED) || remote.optBoolean(ENABLED, false) != enabled) {
            remote.put(ENABLED, enabled);
        }
        putIfChanged(remote, URL, in.optString(URL).strip());

        String key = in.optString(KEY).strip();
        String secret = in.optString(SECRET).strip();
        if (key.isEmpty()) {
            putIfChanged(remote, KEY, "");
            putIfChanged(remote, SECRET, "");
        } else {
            putIfChanged(remote, KEY, key);
            if (!secret.isEmpty()) {
                putIfChanged(remote, SECRET, secret);
            }
        }
    }

    private static void putIfChanged(JSONObject target, String key, String value) {
        if (!target.has(key) || !value.equals(target.optString(key))) {
            target.put(key, value);
        }
    }

    private static boolean validCredential(String s) {
        return s.length() <= MAX_CREDENTIAL && s.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    /** True for an absolute http or https URL with a host. */
    public static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception ex) {
            return false;
        }
    }
}
