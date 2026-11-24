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
import com.electronwill.nightconfig.core.io.ParsingException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class PikaConfig {

    private static final String VERSION = "1.0";

    private static final Logger logger = LoggerFactory.getLogger(PikaConfig.class);

    /** The first-run config file, with comments for each setting */
    private static final String TEMPLATE = "/default-config.toml";

    /** The state file's name, in the same folder as the config file */
    static final String STATE_FILE = ".PikaReaderState.toml";

    /** The UnitID if the hostname can't be found */
    static final String DEFAULT_UNIT_ID = "PikaReader";

    /**
     * The config as the rest of PikaReader sees it. getKey() hands out the
     * live sub-objects, which callers change and then save with putObject().
     */
    private JSONObject configRoot;

    /**
     * The config file as parsed, with its comments and key order. Each save
     * merges configRoot into it so the user's comments stay in the file.
     */
    private CommentedConfig document;

    private final File configFile = new File(System.getProperty("CONFIG", System.getProperty("user.home") + "/.PikaReader.toml"));
    
    ZoneId zoneId = ZoneId.systemDefault();

    /** A "Timezone" of whole hours, such as "-6" or "+10" */
    private static final Pattern OFFSET_HOURS = Pattern.compile("[+-]\\d+");

    /** Set once the web UI has changed the settings, until the next restart */
    private volatile boolean restartPending = false;

    /**
     * SingletonHolder is loaded on the first execution of
     * Singleton.getInstance() or the first access to SingletonHolder.INSTANCE,
     * not before.
     */
    private static class SingletonHolder {

        private static final PikaConfig INSTANCE = new PikaConfig();

    }

    public static PikaConfig getInstance() {

        return SingletonHolder.INSTANCE;
    }

    private  PikaConfig() {
        loadConfig();
    }

    private synchronized void loadConfig() {
        logger.trace("Starting PikaConfig::loadConfig()");
        
        
        // does the config file exist? If not, start from the commented template
        if (!configFile.exists()) {
            try (InputStream in = PikaConfig.class.getResourceAsStream(TEMPLATE)) {
                if (in == null) {
                    throw new IOException(TEMPLATE + " is missing from the jar");
                }
                Path parent = configFile.toPath().toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(in, configFile.toPath());
                logger.info("Created new config file at {}", configFile.getAbsolutePath());
            } catch (IOException ex) {
                logger.error("Unable to create the config file at {}", configFile.getAbsolutePath(), ex);
                System.exit(0);
            }
        } else {
            logger.info("Reading existing config file at {}", configFile.getAbsolutePath());
        }

        // Count the lines as the parser reads them, since its errors don't say where
        LineNumberReader lines = null;
        try (LineNumberReader r = new LineNumberReader(Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8))) {
            lines = r;
            document = TomlJson.parse(r);
            configRoot = TomlJson.toJson(document);
        } catch (ParsingException ex) {
            logger.error("Error in config file {} near line {}: {}", configFile.getAbsolutePath(),
                    // the parser has read the line break at the end of the bad line by then
                    lines == null ? "?" : Math.max(1, lines.getLineNumber()),
                    ex.getMessage().strip().replace("\n", "\\n").replace("\r", "\\r"));
            System.exit(0);
        } catch (Exception ex) {
            logger.error("Unable to read config file at {}", configFile.getAbsolutePath(), ex);
            System.exit(0);
        }
        logger.debug("Read config file with version {}", configRoot.optString("Version"));
        if (!configRoot.optString("Version").equals(VERSION)) {
            logger.info("Version mismatch, updating to {}", VERSION);
            putValue("Version", VERSION);
        }
        
        // look for some defaults:
        
        // UnitID: defaults to the computer's hostname, saved on the first run
        // so the unit keeps its name. The IP is only logged: units normally
        // use DHCP, so it changes from run to run.
        if (configRoot.has("UnitIP")) {
            configRoot.remove("UnitIP");
            saveConfig();
        }
        if (configRoot.optString("UnitID").isBlank()) {
            putValue("UnitID", hostname());
        }
        logger.info("PikaConfig: UnitID {}, IP {}", getStringValue("UnitID"), localIP());

        // Timezone Settings
        
        // If we don't have a timezone set in the config, create a default value. 
        if (configRoot.optString("Timezone").isBlank()){
            putValue("Timezone", "AUTO");
        }
        
        // Now let's translate what the timezone value is into a ZoneID
        try {
            zoneId = parseTimezone(getStringValue("Timezone"));
        } catch (Exception e) {
            logger.error("Unable to parse timezone {}, falling back to {}", getStringValue("Timezone"), zoneId.toString());
        }

        logger.info("PikaReader Timezone: using zoneID " + zoneId.toString() + " Offset: " + ZonedDateTime.now(zoneId).getOffset());
        
        // Do they want the "chip" in hex or decimal?
        if (configRoot.optString("ChipFormat").isBlank()){
            putValue("ChipFormat","Decimal");
            
        }
        
        if (!configRoot.has("StatusFile")){
            putValue("StatusFile","");
        }

        logger.trace("Exiting PikaConfig::loadConfig()");
    }

    /**
     * The zone for a "Timezone" setting: "AUTO" (or blank) for the system
     * timezone, an offset such as "-6" or "-06:00", or a zone ID such as
     * "America/Chicago".
     *
     * @throws java.time.DateTimeException if it is none of those
     */
    static ZoneId parseTimezone(String timezone) {
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

    /**
     * Change several settings and save the file once. Used by the web UI's
     * settings page; the changes take effect when PikaReader restarts.
     */
    public synchronized void edit(Consumer<JSONObject> change) {
        change.accept(configRoot);
        restartPending = true;
        saveConfig();
    }

    /**
     * Change several settings that take effect right away (such as the
     * [Remote] section) and save the file once. Unlike edit(), this doesn't
     * mark a restart as pending.
     */
    public synchronized void editLive(Consumer<JSONObject> change) {
        change.accept(configRoot);
        saveConfig();
    }

    /** True once the settings have been changed since PikaReader started. */
    public boolean isRestartPending() {
        return restartPending;
    }

    private synchronized void saveConfig() {
        // Written so that pulling the power mid-save leaves either the old
        // config or the new one, never a partial file.
        logger.info("Saving config");
        try {
            AtomicFiles.write(configFile.toPath(), file -> {
                TomlJson.merge(document, configRoot);
                TomlJson.write(document, file);
            });
            logger.info("Saved PikaReader config file to {}", configFile.getAbsolutePath());
        } catch (IOException ex) {
            logger.error("Unable to save config file at {}", configFile.getAbsolutePath(), ex);
        }
    }

    public JSONObject getKey(String key) {
        return configRoot.optJSONObject(key, new JSONObject());
    }

    /**
     * A copy of a section, taken under the config lock, for threads that read
     * a section while the web UI may be changing it (e.g. [Remote]).
     */
    public synchronized JSONObject getKeyCopy(String key) {
        JSONObject o = configRoot.optJSONObject(key);
        return o == null ? new JSONObject() : new JSONObject(o.toString());
    }

    public JSONArray getArray(String key) {
        return configRoot.optJSONArray(key);
    }

    public void putArray(String key, JSONArray array) {
        configRoot.put(key, array);
        saveConfig();
    }

    public void putObject(String key, JSONObject object) {
        configRoot.put(key, object);
        saveConfig();
    }

    public void putValue(String key, Object value) {
        configRoot.put(key, value);
        saveConfig();
    }

    public String getStringValue(String key) {
        return configRoot.optString(key);
    }

    public Integer getIntegerValue(String key) {
        return configRoot.optIntegerObject(key);
    }
    
    public ZoneId getTimezoneId(){
        return zoneId;
    }

    /**
     * The state file (profile, reading), kept next to the config file so the
     * config itself is hardly ever rewritten. See PikaState.
     */
    public Path getStatePath() {
        return configFile.toPath().toAbsolutePath().resolveSibling(STATE_FILE);
    }

    /**
     * This computer's hostname without the domain, e.g. "pr4-01" for
     * pr4-01.local. Reads the kernel's hostname on Linux, so no DNS or mDNS
     * lookup is needed.
     */
    static String hostname() {
        String name = "";
        try {
            Path kernel = Path.of("/proc/sys/kernel/hostname");
            if (Files.isReadable(kernel)) {
                name = Files.readString(kernel, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ex) {
            logger.debug("Unable to read the kernel hostname", ex);
        }
        if (unqualified(name).isEmpty()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException ex) {
                logger.debug("Unable to look up the hostname", ex);
            }
        }
        String host = unqualified(name);
        return host.isEmpty() ? DEFAULT_UNIT_ID : host;
    }

    /** The hostname up to the first dot, or "" if there isn't one. */
    static String unqualified(String name) {
        if (name == null) {
            return "";
        }
        String n = name.strip();
        int dot = n.indexOf('.');
        return (dot >= 0 ? n.substring(0, dot) : n).strip();
    }

    /** The first IPv4 address on an interface that is up, for the log. */
    private static String localIP() {
        try {
            for (NetworkInterface ni : NetworkInterface.networkInterfaces().toList()) {
                if (ni.isUp() && !ni.isLoopback() && ni.getHardwareAddress() != null) {
                    for (InetAddress a : ni.inetAddresses().toList()) {
                        if (a.getAddress().length == 4) {
                            return a.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ex) {
            logger.debug("Unable to list the network interfaces", ex);
        }
        return "UNKNOWN";
    }
}
