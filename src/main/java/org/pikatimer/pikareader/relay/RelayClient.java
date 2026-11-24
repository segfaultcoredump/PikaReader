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
package org.pikatimer.pikareader.relay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pikatimer.pikareader.conf.PikaConfig;
import org.pikatimer.pikareader.conf.RemoteSettings;
import org.pikatimer.pikareader.readers.ReaderHandler;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.status.StatusHandler;
import org.pikatimer.pikareader.tags.TagDB;
import org.pikatimer.pikareader.tags.TagRead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends reads and status to a PikaRelay server and runs the commands it hands
 * back (start, rewind, setProfile, forceSetProfile). See PikaRelay's
 * docs/API.md for the wire contract.
 *
 * Every 5 seconds the status thread PUTs the current status and polls for
 * commands, and the reads thread uploads the reads queued since the last
 * cycle. The two run on their own threads, so a long catch-up upload never
 * delays the status. Commands run one at a time on a third thread, since
 * starting the readers or a rewind can take a while.
 *
 * Reads are queued in memory while the relay can't be reached and sent once
 * it can. The queue is not kept over a restart: the timing side can send a
 * rewind command for anything that was lost. A rewind always uploads the
 * whole range, whether or not those reads were sent before; the relay skips
 * the ones it already has.
 *
 * The [Remote] settings are read again on every cycle (and right away when
 * the web UI saves them), so changes take effect without a restart.
 */
public class RelayClient {

    private static final Logger logger = LoggerFactory.getLogger(RelayClient.class);

    static final long CYCLE_MS = 5000;
    /** PikaRelay takes at most 2000 reads (and 2 MB) per upload */
    static final int MAX_BATCH = 2000;
    static final int MIN_BATCH = 100;
    /** Longest rewind we will run. PikaRelay's own limit is 7 days by default. */
    static final long MAX_REWIND_MS = Duration.ofDays(31).toMillis();
    /** A rewind reads and sends this much at a time, to bound the memory */
    static final long REWIND_SLICE_MS = Duration.ofDays(1).toMillis();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // PikaRelay's limits on a status body
    static final int MAX_STATUS_DEPTH = 8;
    static final int MAX_STATUS_STRING = 1024;
    static final int MAX_STATUS_BYTES = 256 * 1024;
    private static final Pattern STATUS_KEY = Pattern.compile("[A-Za-z0-9 _/.-]{1,64}");

    // PikaRelay's rules for a read
    private static final Pattern CHIP = Pattern.compile("[0-9A-Fa-f]{1,64}");

    /** The [Remote] settings in use. Null when sending is off or not usable. */
    record Settings(String base, String unitId, String key, String secret) {

        @Override
        public String toString() {
            // Never print the secret
            return base + " as " + unitId;
        }
    }

    /** What the commands do to this unit. Replaced in tests. */
    interface RelayActions {

        boolean isReading();

        void startReading();

        void stopReading();

        /** @throws IllegalArgumentException unknown profile
         * @throws IllegalStateException while reading */
        void setProfile(String profile);

        /** The id of the profile with this id or name, or null if none. */
        String findProfile(String profile);

        String currentProfile();
    }

    /** Where rewinds get their reads. Replaced in tests. */
    interface ReadSource {

        /** Reads with epochMilli in [fromMs, toMs), sorted by time. */
        List<TagRead> readsBetween(long fromMs, long toMs);
    }

    private record Response(int status, String body, String location) {

        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** "HTTP 401 (Invalid credentials)" */
        String describe() {
            if (status >= 300 && status < 400) {
                return "HTTP " + status + ": redirected to " + location + ". Set the PikaRelay URL to that address.";
            }
            String error = null;
            try {
                error = new JSONObject(body).optString("error", null);
            } catch (JSONException ex) {
                // not a PikaRelay error body
            }
            return "HTTP " + status + (error == null || error.isBlank() ? "" : " (" + error + ")");
        }
    }

    private final Supplier<JSONObject> remoteConfig;
    private final Supplier<String> unitId;
    private final Supplier<JSONObject> status;
    private final Supplier<ZoneId> zone;
    private final RelayActions actions;
    private final ReadSource readSource;
    private final Executor commandExecutor;
    private final HttpClient http;

    // Reads waiting to be sent, oldest first. Only the reads thread takes
    // from the head (and puts a failed batch back there); the router adds to
    // the tail.
    private final ConcurrentLinkedDeque<TagRead> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger pendingReads = new AtomicInteger();
    private volatile int batchSize = MAX_BATCH;

    private volatile Settings settings;
    private volatile boolean enabled;

    // Commands handed to the command thread and not finished yet
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    // Commands that were run but whose acknowledgement failed: id -> expiry
    private final Map<Long, Long> unacknowledged = new ConcurrentHashMap<>();

    // Connection state, for the log and the status page
    private final Object stateLock = new Object();
    private volatile boolean connected = false;
    private volatile boolean everFailed = false;
    private volatile Instant lastContact;
    private volatile String lastError;
    private String lastFailure;
    private String loggedConfigProblem;
    private boolean loaded = false;

    private ScheduledExecutorService statusScheduler;
    private ScheduledExecutorService readsScheduler;
    private ExecutorService commandThread;

    private static class SingletonHolder {

        private static final RelayClient INSTANCE = createDefault();
    }

    public static RelayClient getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private static RelayClient createDefault() {
        PikaConfig config = PikaConfig.getInstance();
        ExecutorService commands = Executors.newSingleThreadExecutor(daemon("RelayCommands"));
        RelayActions actions = new RelayActions() {
            @Override
            public boolean isReading() {
                return ReaderHandler.getInstance().isReading();
            }

            @Override
            public void startReading() {
                ReaderHandler.getInstance().startReading();
            }

            @Override
            public void stopReading() {
                ReaderHandler.getInstance().stopReading();
            }

            @Override
            public void setProfile(String profile) {
                ReaderHandler.getInstance().setProfile(profile);
            }

            @Override
            public String findProfile(String profile) {
                return ProfileManager.getInstance().find(profile).map(p -> p.id()).orElse(null);
            }

            @Override
            public String currentProfile() {
                return ProfileManager.getInstance().getCurrent().id();
            }
        };
        RelayClient client = new RelayClient(
                () -> config.getKeyCopy(RemoteSettings.REMOTE),
                () -> config.getStringValue("UnitID"),
                () -> StatusHandler.getInstance().getStatus(),
                config::getTimezoneId,
                actions,
                (from, to) -> TagDB.getInstance().getReadsBetween(from, to),
                commands);
        client.commandThread = commands;
        client.start();
        return client;
    }

    RelayClient(Supplier<JSONObject> remoteConfig, Supplier<String> unitId, Supplier<JSONObject> status,
            Supplier<ZoneId> zone, RelayActions actions, ReadSource readSource, Executor commandExecutor) {
        this.remoteConfig = remoteConfig;
        this.unitId = unitId;
        this.status = status;
        this.zone = zone;
        this.actions = actions;
        this.readSource = readSource;
        this.commandExecutor = commandExecutor;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // A redirected PUT or POST would turn into a GET; say so instead
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        reloadSettings();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    private void start() {
        statusScheduler = Executors.newSingleThreadScheduledExecutor(daemon("RelayStatus"));
        readsScheduler = Executors.newSingleThreadScheduledExecutor(daemon("RelayReads"));
        statusScheduler.scheduleWithFixedDelay(this::statusCycle, 1000, CYCLE_MS, TimeUnit.MILLISECONDS);
        readsScheduler.scheduleWithFixedDelay(this::readsCycle, 2500, CYCLE_MS, TimeUnit.MILLISECONDS);
        logger.info("PikaRelay client started{}", settings == null ? " (sending is off)" : ": sending to " + settings);
    }

    /** Stop the relay threads. Queued reads are not sent. */
    public void shutdown() {
        if (statusScheduler != null) {
            statusScheduler.shutdownNow();
        }
        if (readsScheduler != null) {
            readsScheduler.shutdownNow();
        }
        if (commandThread != null) {
            commandThread.shutdownNow();
        }
    }

    // scheduleWithFixedDelay() never runs a task again once it throws
    private void statusCycle() {
        try {
            reloadSettings();
            Settings s = settings;
            if (s != null && sendStatus(s)) {
                pollCommands(s);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            logger.error("Error in the PikaRelay status cycle", ex);
        }
    }

    private void readsCycle() {
        try {
            sendQueuedReads();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            logger.error("Error sending reads to PikaRelay", ex);
        }
    }

    /**
     * Read the [Remote] settings again. Called every cycle and by the web UI
     * right after it saves them. Turning sending off drops the queued reads.
     */
    public final void reloadSettings() {
        JSONObject remote = remoteConfig.get();
        boolean on = remote.optBoolean(RemoteSettings.ENABLED, false);
        Settings s = null;
        String problem = null;
        if (on) {
            String url = remote.optString(RemoteSettings.URL).strip();
            String id = unitId.get();
            if (url.isEmpty()) {
                problem = "No PikaRelay URL is set";
            } else if (!RemoteSettings.isHttpUrl(url)) {
                problem = "The PikaRelay URL '" + url + "' isn't an http:// or https:// address";
            } else if (id == null || !RemoteSettings.RELAY_ID.matcher(id).matches()) {
                problem = RemoteSettings.RELAY_ID_RULE + " (it is '" + id + "')";
            } else {
                while (url.endsWith("/")) {
                    url = url.substring(0, url.length() - 1);
                }
                String key = remote.optString(RemoteSettings.KEY).strip();
                String secret = remote.optString(RemoteSettings.SECRET).strip();
                s = new Settings(url, id, key, secret);
            }
        }

        synchronized (stateLock) {
            Settings old = settings;
            settings = s;
            if (enabled && !on) {
                int dropped = clearQueue();
                logger.info("Sending to PikaRelay turned off{}", dropped > 0 ? ", dropped " + dropped + " queued reads" : "");
                connected = false;
                everFailed = false;
                lastError = null;
            } else if (s != null && !s.equals(old)) {
                if (!loaded) {
                    // start() logs where it is sending
                } else if (old != null) {
                    logger.info("PikaRelay settings changed: sending to {}", s);
                } else if (enabled) {
                    logger.info("Sending to PikaRelay at {}", s);
                } else {
                    logger.info("Sending to PikaRelay turned on: sending to {}", s);
                }
                connected = false;
                everFailed = false;
                lastError = null;
                lastFailure = null;
                loggedConfigProblem = null;
            }
            enabled = on;
            loaded = true;
            if (problem != null) {
                lastError = problem;
                connected = false;
                if (!problem.equals(loggedConfigProblem)) {
                    logger.error("Unable to send to PikaRelay: {}", problem);
                    loggedConfigProblem = problem;
                }
            }
        }
    }

    /**
     * Queue reads for the relay. Called by the router for every batch of
     * reads; does nothing while sending is off. Never blocks.
     */
    public void addReads(Collection<TagRead> reads) {
        if (!enabled || reads.isEmpty()) {
            return;
        }
        queue.addAll(reads);
        pendingReads.addAndGet(reads.size());
    }

    private int clearQueue() {
        int dropped = 0;
        while (queue.pollFirst() != null) {
            dropped++;
        }
        pendingReads.addAndGet(-dropped);
        return dropped;
    }

    /** The relay connection state for the status report and settings page. */
    public JSONObject statusJSON() {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("connected", enabled && connected);
        o.put("pendingReads", pendingReads.get());
        Instant contact = lastContact;
        if (contact != null) {
            o.put("lastContact", contact.toString());
            o.put("lastContactLocal", LocalDateTime.ofInstant(contact, zone.get()).withNano(0).toString());
        }
        String error = lastError;
        if (enabled && error != null) {
            o.put("lastError", error);
        }
        return o;
    }

    /** The settings in use, or null when sending is off or not usable. */
    Settings currentSettings() {
        return settings;
    }

    int getPendingReads() {
        return pendingReads.get();
    }

    // ---- connection state ----
    private void reached() {
        synchronized (stateLock) {
            lastContact = Instant.now();
            if (!connected) {
                Settings s = settings;
                if (everFailed) {
                    logger.info("PikaRelay at {} is reachable again. {} reads waiting to be sent.", s == null ? "?" : s.base(), pendingReads.get());
                } else {
                    logger.info("Connected to PikaRelay at {}", s == null ? "?" : s.base());
                }
                connected = true;
            }
            lastError = null;
            lastFailure = null;
        }
    }

    private void failed(String what, String error) {
        synchronized (stateLock) {
            String message = what + ": " + error;
            if (connected || !everFailed) {
                Settings s = settings;
                logger.warn("Unable to reach PikaRelay at {} ({}). Reads are queued until it can be reached.",
                        s == null ? "?" : s.base(), message);
            } else if (!error.equals(lastFailure)) {
                logger.info("PikaRelay still unreachable: {}", message);
            }
            connected = false;
            everFailed = true;
            lastError = message;
            lastFailure = error;
        }
    }

    private static String describe(Exception ex) {
        String m = ex.getMessage();
        return ex.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : " (" + m + ")");
    }

    // ---- HTTP ----
    private Response call(Settings s, String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(s.base() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "PikaReader");
        if (!s.key().isEmpty()) {
            b.header("X-Pika-Key", s.key());
            b.header("X-Pika-Secret", s.secret());
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(""));
    }

    // ---- status ----
    /** PUT the status. Returns true if the relay took it. */
    boolean sendStatus(Settings s) throws InterruptedException {
        JSONObject current = status.get();
        if (current == null || current.isEmpty()) {
            return true;
        }
        JSONObject body;
        try {
            body = (JSONObject) sanitize(current, 1);
        } catch (RuntimeException ex) {
            // The status thread swapped it out from under us; next time
            logger.debug("Unable to copy the status for PikaRelay", ex);
            return true;
        }
        String json = body.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_STATUS_BYTES) {
            logger.warn("Status is too large for PikaRelay ({} bytes), not sent", json.length());
            return true;
        }
        try {
            Response r = call(s, "PUT", "/readerStatus/" + s.unitId(), json);
            if (r.ok()) {
                reached();
                return true;
            }
            if (r.status() == 422 || r.status() == 413) {
                // Reached, but it didn't like this status
                reached();
                logger.warn("PikaRelay rejected the status: {}", r.describe());
                return true;
            }
            failed("status", r.describe());
        } catch (IOException ex) {
            failed("status", describe(ex));
        }
        return false;
    }

    /**
     * A copy of a status value that keeps to PikaRelay's limits: key names
     * from [A-Za-z0-9 _/.-], strings of at most 1024 characters with no
     * control characters or backslashes, and at most 8 levels of nesting.
     * Anything that can't be fixed up is left out.
     */
    static Object sanitize(Object v, int depth) {
        if (v instanceof JSONObject o) {
            if (depth > MAX_STATUS_DEPTH) {
                return null;
            }
            JSONObject out = new JSONObject();
            for (String k : new ArrayList<>(o.keySet())) {
                if (!STATUS_KEY.matcher(k).matches()) {
                    continue;
                }
                Object c = sanitize(o.opt(k), depth + 1);
                if (c != null) {
                    out.put(k, c);
                }
            }
            return out;
        }
        if (v instanceof JSONArray a) {
            if (depth > MAX_STATUS_DEPTH) {
                return null;
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                Object c = sanitize(a.opt(i), depth + 1);
                out.put(c == null ? JSONObject.NULL : c);
            }
            return out;
        }
        if (v instanceof String str) {
            return cleanString(str);
        }
        if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return null;
        }
        if (v instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return null;
        }
        if (v instanceof Number || v instanceof Boolean || JSONObject.NULL.equals(v)) {
            return v;
        }
        return v == null ? null : cleanString(v.toString());
    }

    private static String cleanString(String s) {
        StringBuilder b = new StringBuilder(Math.min(s.length(), MAX_STATUS_STRING));
        for (int i = 0; i < s.length() && b.length() < MAX_STATUS_STRING; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                b.append('/');
            } else if (Character.isISOControl(c)) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        // Don't leave half of a surrogate pair at the cut
        if (b.length() > 0 && Character.isHighSurrogate(b.charAt(b.length() - 1))) {
            b.setLength(b.length() - 1);
        }
        return b.toString();
    }

    // ---- commands ----
    /** Fetch the pending commands and hand the new ones to the command thread. */
    void pollCommands(Settings s) throws InterruptedException {
        Response r;
        try {
            r = call(s, "GET", "/commands/" + s.unitId(), null);
        } catch (IOException ex) {
            failed("commands", describe(ex));
            return;
        }
        if (!r.ok()) {
            failed("commands", r.describe());
            return;
        }
        reached();
        JSONArray commands;
        try {
            commands = new JSONObject(r.body()).optJSONArray("commands", new JSONArray());
        } catch (JSONException ex) {
            logger.warn("PikaRelay sent a command list that isn't JSON: {}", ex.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        unacknowledged.values().removeIf(expires -> expires > 0 && expires < now);
        for (int i = 0; i < commands.length(); i++) {
            JSONObject c = commands.optJSONObject(i);
            if (c == null || c.optLong("id", -1) < 0) {
                continue;
            }
            long id = c.getLong("id");
            if (unacknowledged.containsKey(id)) {
                // Already run; only the acknowledgement is missing
                acknowledge(id, c.optLong("expires", 0));
                continue;
            }
            if (inFlight.add(id)) {
                try {
                    commandExecutor.execute(() -> runCommand(c));
                } catch (RuntimeException ex) {
                    inFlight.remove(id);
                    logger.warn("Unable to run PikaRelay command {}", id, ex);
                }
            }
        }
    }

    /** Run one command and acknowledge it, unless it should be tried again. */
    void runCommand(JSONObject c) {
        long id = c.getLong("id");
        try {
            boolean done = execute(c);
            if (done) {
                acknowledge(id, c.optLong("expires", 0));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            // Acknowledge it: the same command would most likely fail again
            logger.error("PikaRelay command {} ({}) failed", id, c.optString("command"), ex);
            try {
                acknowledge(id, c.optLong("expires", 0));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            inFlight.remove(id);
        }
    }

    /**
     * Carry out a command.
     *
     * @return true when it is done with (acknowledge it), false to leave it
     * for the relay to hand back on a later poll
     */
    private boolean execute(JSONObject c) throws InterruptedException {
        long id = c.getLong("id");
        String command = c.optString("command");
        switch (command) {
            case "start" -> {
                if (actions.isReading()) {
                    logger.info("PikaRelay command {}: start. Already reading.", id);
                } else {
                    logger.info("PikaRelay command {}: start. Starting the readers.", id);
                    actions.startReading();
                }
                return true;
            }
            case "setProfile" -> {
                String profile = c.optString("profile");
                logger.info("PikaRelay command {}: set the reader profile to {}", id, profile);
                try {
                    actions.setProfile(profile);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    logger.warn("PikaRelay command {}: unable to set the profile to {}: {}", id, profile, ex.getMessage());
                }
                return true;
            }
            case "forceSetProfile" -> {
                String profile = c.optString("profile");
                String found = actions.findProfile(profile);
                if (found == null) {
                    logger.warn("PikaRelay command {}: unknown reader profile '{}'", id, profile);
                    return true;
                }
                if (found.equals(actions.currentProfile())) {
                    logger.info("PikaRelay command {}: force set the profile to {}. It is already the current profile.", id, found);
                    return true;
                }
                boolean wasReading = actions.isReading();
                logger.info("PikaRelay command {}: force set the profile to {}{}", id, found,
                        wasReading ? ", stopping and restarting the readers" : "");
                if (wasReading) {
                    actions.stopReading();
                }
                try {
                    actions.setProfile(found);
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    logger.warn("PikaRelay command {}: unable to set the profile to {}: {}", id, found, ex.getMessage());
                } finally {
                    if (wasReading) {
                        actions.startReading();
                    }
                }
                return true;
            }
            case "rewind" -> {
                long from = c.optLong("from", -1);
                long to = c.optLong("to", -1);
                if (from < 0 || to <= from || to - from > MAX_REWIND_MS) {
                    logger.warn("PikaRelay command {}: rewind with a bad range ({} to {}), ignored", id, c.opt("from"), c.opt("to"));
                    return true;
                }
                Settings s = settings;
                if (s == null) {
                    // Sending was turned off; leave it for later
                    return false;
                }
                logger.info("PikaRelay command {}: rewind {} to {}", id, Instant.ofEpochMilli(from), Instant.ofEpochMilli(to));
                return rewind(s, id, from, to);
            }
            default -> {
                logger.warn("PikaRelay command {}: unknown command '{}', ignored", id, command);
                return true;
            }
        }
    }

    private void acknowledge(long id, long expires) throws InterruptedException {
        Settings s = settings;
        if (s == null) {
            return;
        }
        try {
            Response r = call(s, "DELETE", "/commands/" + s.unitId() + "/" + id, null);
            // 404: it is gone already (expired or deleted)
            if (r.ok() || r.status() == 404) {
                unacknowledged.remove(id);
                return;
            }
            logger.warn("Unable to acknowledge PikaRelay command {}: {}", id, r.describe());
        } catch (IOException ex) {
            logger.warn("Unable to acknowledge PikaRelay command {}: {}", id, describe(ex));
        }
        unacknowledged.put(id, expires);
    }

    /**
     * Upload every stored read with epochMilli in [from, to), whether or not
     * it was sent before. The range is exact (epoch ms), so daylight saving
     * or a Timezone change inside it doesn't matter. Works a day at a time to
     * keep the memory down.
     *
     * @return true when every read was sent
     */
    boolean rewind(Settings s, long commandId, long from, long to) throws InterruptedException {
        long sent = 0;
        long dropped = 0;
        for (long start = from; start < to; start = Math.min(to, start + REWIND_SLICE_MS)) {
            long end = Math.min(to, start + REWIND_SLICE_MS);
            List<TagRead> reads = readSource.readsBetween(start, end);
            for (int i = 0; i < reads.size(); i += MAX_BATCH) {
                List<TagRead> batch = reads.subList(i, Math.min(reads.size(), i + MAX_BATCH));
                if (!upload(s, batch)) {
                    logger.warn("PikaRelay command {}: rewind stopped after {} reads. It is retried while the command lasts.", commandId, sent);
                    return false;
                }
                int valid = (int) batch.stream().filter(RelayClient::isValid).count();
                sent += valid;
                dropped += batch.size() - valid;
            }
        }
        if (dropped > 0) {
            logger.info("PikaRelay command {}: skipped {} stored reads PikaRelay won't take (no epochMilli or a bad chip)", commandId, dropped);
        }
        logger.info("PikaRelay command {}: rewind sent {} reads", commandId, sent);
        return true;
    }

    // ---- reads ----
    /** Send everything queued, in batches, until the queue is empty or a send fails. */
    void sendQueuedReads() throws InterruptedException {
        while (true) {
            Settings s = settings;
            if (s == null) {
                return;
            }
            List<TagRead> batch = new ArrayList<>(Math.min(batchSize, Math.max(pendingReads.get(), 1)));
            TagRead r;
            while (batch.size() < batchSize && (r = queue.pollFirst()) != null) {
                batch.add(r);
            }
            if (batch.isEmpty()) {
                return;
            }
            boolean sent;
            try {
                sent = upload(s, batch);
            } catch (InterruptedException | RuntimeException ex) {
                requeue(batch);
                throw ex;
            }
            if (!sent) {
                requeue(batch);
                return;
            }
            pendingReads.addAndGet(-batch.size());
        }
    }

    // Put a batch back at the head of the queue, in its order
    private void requeue(List<TagRead> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            queue.offerFirst(batch.get(i));
        }
    }

    /**
     * Upload reads to the relay. Reads that break PikaRelay's rules are
     * dropped here, since one bad read would get the whole batch rejected. If
     * the relay still rejects the batch (422) or finds it too large (413) it
     * is split in two and each half sent, down to single reads, which are
     * dropped with an error. The dropped reads are still on disk.
     *
     * @return true when the reads are done with: stored or dropped
     */
    boolean upload(Settings s, List<TagRead> reads) throws InterruptedException {
        List<TagRead> valid = new ArrayList<>(reads.size());
        for (TagRead r : reads) {
            if (isValid(r)) {
                valid.add(r);
            } else {
                logger.debug("Not sending read to PikaRelay, it breaks the relay's rules: {}", r.toJSON());
            }
        }
        return valid.isEmpty() || uploadValid(s, valid);
    }

    private boolean uploadValid(Settings s, List<TagRead> reads) throws InterruptedException {
        StringBuilder body = new StringBuilder(reads.size() * 180);
        body.append('[');
        for (int i = 0; i < reads.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(reads.get(i).toJSON());
        }
        body.append(']');
        Response r;
        try {
            r = call(s, "POST", "/putReads/" + s.unitId(), body.toString());
        } catch (IOException ex) {
            failed("reads", describe(ex));
            return false;
        }
        if (r.ok()) {
            reached();
            logger.trace("Sent {} reads to PikaRelay: {}", reads.size(), r.body());
            return true;
        }
        if (r.status() == 422 || r.status() == 413) {
            reached();
            if (r.status() == 413 && reads.size() > MIN_BATCH) {
                batchSize = Math.max(MIN_BATCH, Math.min(batchSize, reads.size() / 2));
            }
            if (reads.size() == 1) {
                logger.error("PikaRelay rejected read {}: {}. It is kept on disk but not sent.", reads.get(0).toJSON(), r.describe());
                return true;
            }
            int half = reads.size() / 2;
            return uploadValid(s, reads.subList(0, half)) && uploadValid(s, reads.subList(half, reads.size()));
        }
        failed("reads", r.describe());
        return false;
    }

    /** True if PikaRelay will take this read. */
    static boolean isValid(TagRead r) {
        String chip = r.getChip();
        Long ms = r.getEpochMilli();
        return chip != null && CHIP.matcher(chip).matches()
                && ms != null && ms > 0
                && inRange(r.getReaderID()) && inRange(r.getReaderAntenna());
    }

    private static boolean inRange(Integer i) {
        return i == null || (i >= 0 && i <= 65535);
    }
}
