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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pikatimer.pikareader.tags.TagRead;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives RelayClient's cycles by hand against a fake PikaRelay.
 */
class RelayClientTest {

    private static final String UNIT = "pr4-01";
    private static final ZoneId ZONE = ZoneOffset.UTC;

    /** One request the fake relay got */
    record Request(String method, String path, String body, String key, String secret) {

        JSONArray reads() {
            return new JSONArray(body);
        }
    }

    record Reply(int status, String body) {
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    /** Override the fake relay's answer; return null for the default */
    private volatile Function<Request, Reply> override = r -> null;
    private volatile JSONArray pendingCommands = new JSONArray();

    private JSONObject remote;
    private JSONObject status;
    private final List<String> actionsLog = Collections.synchronizedList(new ArrayList<>());
    private boolean reading = false;
    private String profile = "finish_mats";
    private final List<TagRead> stored = new ArrayList<>();
    private RelayClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request req = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body,
                    exchange.getRequestHeaders().getFirst("X-Pika-Key"), exchange.getRequestHeaders().getFirst("X-Pika-Secret"));
            requests.add(req);
            Reply reply = override.apply(req);
            if (reply == null) {
                reply = defaultReply(req);
            }
            byte[] out = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();

        remote = new JSONObject().put("Enabled", true).put("URL", url() + "/").put("Key", "").put("Secret", "");
        status = new JSONObject().put("unitID", UNIT).put("reading", false).put("type", "STATUS");

        RelayClient.RelayActions actions = new RelayClient.RelayActions() {
            @Override
            public boolean isReading() {
                return reading;
            }

            @Override
            public void startReading() {
                actionsLog.add("start");
                reading = true;
            }

            @Override
            public void stopReading() {
                actionsLog.add("stop");
                reading = false;
            }

            @Override
            public void setProfile(String p) {
                if (findProfile(p) == null) {
                    throw new IllegalArgumentException("Unknown reader profile '" + p + "'");
                }
                if (reading) {
                    throw new IllegalStateException("Stop reading before changing the reader profile");
                }
                actionsLog.add("profile " + p);
                profile = p;
            }

            @Override
            public String findProfile(String p) {
                return List.of("finish_mats", "start_mats").contains(p) ? p : null;
            }

            @Override
            public String currentProfile() {
                return profile;
            }
        };
        RelayClient.ReadSource source = (from, to) -> stored.stream()
                .filter(r -> r.getEpochMilli() >= from && r.getEpochMilli() < to).sorted().toList();

        client = new RelayClient(() -> new JSONObject(remote.toString()), () -> UNIT, () -> status, () -> ZONE,
                actions, source, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Reply defaultReply(Request req) {
        String p = req.path();
        if (req.method().equals("PUT") && p.equals("/readerStatus/" + UNIT)) {
            return new Reply(204, null);
        }
        if (req.method().equals("POST") && p.equals("/putReads/" + UNIT)) {
            int n = req.reads().length();
            return new Reply(200, new JSONObject().put("received", n).put("new", n).toString());
        }
        if (req.method().equals("GET") && p.equals("/commands/" + UNIT)) {
            return new Reply(200, new JSONObject().put("serverTime", 1).put("commands", pendingCommands).toString());
        }
        if (req.method().equals("DELETE") && p.startsWith("/commands/" + UNIT + "/")) {
            long id = Long.parseLong(p.substring(p.lastIndexOf('/') + 1));
            JSONArray left = new JSONArray();
            for (int i = 0; i < pendingCommands.length(); i++) {
                if (pendingCommands.getJSONObject(i).getLong("id") != id) {
                    left.put(pendingCommands.getJSONObject(i));
                }
            }
            pendingCommands = left;
            return new Reply(204, null);
        }
        return new Reply(404, "{\"error\":\"Not found\"}");
    }

    private static TagRead read(String chip, long epochMilli) {
        TagRead r = new TagRead();
        r.setEPC(chip);
        r.setChip(chip);
        r.setEpochMilli(epochMilli);
        r.setTimestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZONE));
        r.setTZOffset("Z");
        r.setReaderID(1);
        r.setReaderAntenna(2);
        r.setPeakRSSI(-50.0);
        return r;
    }

    private static List<TagRead> reads(int n, long start) {
        List<TagRead> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(read(Integer.toString(1000 + i), start + i));
        }
        return list;
    }

    private List<Request> requests(String method, String pathPrefix) {
        return requests.stream().filter(r -> r.method().equals(method) && r.path().startsWith(pathPrefix)).toList();
    }

    private List<Long> sentEpochs() {
        List<Long> out = new ArrayList<>();
        for (Request r : requests("POST", "/putReads/")) {
            JSONArray a = r.reads();
            for (int i = 0; i < a.length(); i++) {
                out.add(a.getJSONObject(i).getLong("epochMilli"));
            }
        }
        return out;
    }

    @Test
    void readsAreSentInBatchesOfAtMost2000() throws Exception {
        client.addReads(reads(2500, 1_000_000));
        assertEquals(2500, client.getPendingReads());
        client.sendQueuedReads();
        List<Request> posts = requests("POST", "/putReads/" + UNIT);
        assertEquals(2, posts.size());
        assertEquals(2000, posts.get(0).reads().length());
        assertEquals(500, posts.get(1).reads().length());
        assertEquals(0, client.getPendingReads());
        JSONObject first = posts.get(0).reads().getJSONObject(0);
        assertEquals("1000", first.getString("chip"));
        assertEquals(1_000_000, first.getLong("epochMilli"));
        assertTrue(client.statusJSON().getBoolean("connected"));
    }

    @Test
    void readsWaitWhileTheRelayIsDownAndAreSentInOrderOnceItIsBack() throws Exception {
        override = r -> r.method().equals("POST") ? new Reply(503, "{\"error\":\"Database unavailable\"}") : null;
        client.addReads(reads(10, 1_000_000));
        client.sendQueuedReads();
        client.addReads(reads(5, 2_000_000));
        client.sendQueuedReads();
        assertEquals(15, client.getPendingReads());
        JSONObject state = client.statusJSON();
        assertFalse(state.getBoolean("connected"));
        assertTrue(state.getString("lastError").contains("503"), state.toString());

        override = r -> null;
        requests.clear();
        client.sendQueuedReads();
        assertEquals(0, client.getPendingReads());
        List<Long> expected = new ArrayList<>();
        reads(10, 1_000_000).forEach(r -> expected.add(r.getEpochMilli()));
        reads(5, 2_000_000).forEach(r -> expected.add(r.getEpochMilli()));
        assertEquals(expected, sentEpochs());
    }

    @Test
    void readsWaitWhileTheRelayCantBeReached() throws Exception {
        server.stop(0);
        client.addReads(reads(3, 1_000_000));
        client.sendQueuedReads();
        assertEquals(3, client.getPendingReads());
        assertFalse(client.statusJSON().getBoolean("connected"));
    }

    @Test
    void aRejectedBatchIsSplitAndOnlyTheBadReadDropped() throws Exception {
        long bad = 1_000_037;
        override = r -> r.method().equals("POST") && r.body().contains("\"epochMilli\":" + bad)
                ? new Reply(422, "{\"error\":\"Invalid read\"}") : null;
        client.addReads(reads(100, 1_000_000));
        client.sendQueuedReads();
        assertEquals(0, client.getPendingReads());
        List<Long> sent = sentEpochs().stream().filter(e -> e != bad).distinct().toList();
        assertEquals(99, sent.size());
        // No read that was stored got sent as part of an accepted batch twice
        assertEquals(99, requests("POST", "/putReads/").stream()
                .filter(r -> !r.body().contains("\"epochMilli\":" + bad))
                .mapToInt(r -> r.reads().length()).sum());
    }

    @Test
    void readsTheRelayWouldRejectAreNotSent() throws Exception {
        TagRead noEpoch = read("1234", 1);
        noEpoch.setEpochMilli(0L);
        TagRead badChip = read("12x4", 5);
        client.addReads(List.of(noEpoch, badChip, read("4D2", 7)));
        client.sendQueuedReads();
        assertEquals(List.of(7L), sentEpochs());
        assertEquals(0, client.getPendingReads());
    }

    @Test
    void credentialsAreSentOnlyWhenAKeyIsSet() throws Exception {
        client.sendStatus(client.currentSettings());
        assertNull(requests.get(0).key());
        assertNull(requests.get(0).secret());

        remote.put("Key", "k1").put("Secret", "s1");
        client.reloadSettings();
        client.sendStatus(client.currentSettings());
        assertEquals("k1", requests.get(1).key());
        assertEquals("s1", requests.get(1).secret());
        assertFalse(client.currentSettings().toString().contains("s1"));
    }

    @Test
    void statusIsPutAsIs() throws Exception {
        assertTrue(client.sendStatus(client.currentSettings()));
        Request put = requests("PUT", "/readerStatus/" + UNIT).get(0);
        assertTrue(status.similar(new JSONObject(put.body())));
    }

    @Test
    void statusIsKeptWithinTheRelaysRules() {
        JSONObject s = new JSONObject()
                .put("path", "C:\\temp\\x")
                .put("ctl", "a\nb\tc")
                .put("long", "x".repeat(5000))
                .put("bad key!", 1)
                .put("Raw Tag Reads/s", 5)
                .put("ok", new JSONArray().put("a\\b").put(1));
        JSONObject deep = s;
        for (int i = 0; i < 10; i++) {
            JSONObject next = new JSONObject();
            deep.put("d", next);
            deep = next;
        }
        JSONObject out = (JSONObject) RelayClient.sanitize(s, 1);
        assertEquals("C:/temp/x", out.getString("path"));
        assertEquals("a b c", out.getString("ctl"));
        assertEquals(1024, out.getString("long").length());
        assertFalse(out.has("bad key!"));
        assertEquals(5, out.getInt("Raw Tag Reads/s"));
        assertEquals("a/b", out.getJSONArray("ok").getString(0));
        int depth = 1;
        for (JSONObject o = out; o.has("d"); o = o.getJSONObject("d")) {
            depth++;
        }
        assertEquals(RelayClient.MAX_STATUS_DEPTH, depth);
    }

    @Test
    void commandsAreRunAndAcknowledged() throws Exception {
        pendingCommands = new JSONArray()
                .put(new JSONObject().put("id", 1).put("command", "start"))
                .put(new JSONObject().put("id", 2).put("command", "setProfile").put("profile", "start_mats"))
                .put(new JSONObject().put("id", 3).put("command", "launchRocket"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("start"), actionsLog);
        // setProfile is refused while reading, but still acknowledged
        assertEquals("finish_mats", profile);
        assertEquals(List.of("/commands/pr4-01/1", "/commands/pr4-01/2", "/commands/pr4-01/3"),
                requests("DELETE", "/commands/").stream().map(Request::path).toList());
        assertEquals(0, pendingCommands.length());

        // start while reading does nothing more
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 4).put("command", "start"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("start"), actionsLog);
    }

    @Test
    void setProfileWhileStopped() throws Exception {
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 1).put("command", "setProfile").put("profile", "start_mats"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("profile start_mats"), actionsLog);
    }

    @Test
    void forceSetProfileRestartsOnlyIfItWasReading() throws Exception {
        reading = true;
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 1).put("command", "forceSetProfile").put("profile", "start_mats"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("stop", "profile start_mats", "start"), actionsLog);
        assertTrue(reading);

        actionsLog.clear();
        reading = false;
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 2).put("command", "forceSetProfile").put("profile", "finish_mats"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("profile finish_mats"), actionsLog);
        assertFalse(reading);

        // An unknown profile leaves the readers alone
        actionsLog.clear();
        reading = true;
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 3).put("command", "forceSetProfile").put("profile", "nope"));
        client.pollCommands(client.currentSettings());
        assertEquals(List.of(), actionsLog);
        assertEquals(3, requests("DELETE", "/commands/").size());
    }

    @Test
    void rewindSendsExactlyTheRangeEvenIfSentBefore() throws Exception {
        long day = 86_400_000L;
        long base = 1_795_900_000_000L;
        // Reads over three days, plus one with no epochMilli
        for (long t = base - day; t < base + 2 * day; t += 3_600_000L) {
            stored.add(read(Long.toString(t % 100000 + 1), t));
        }
        TagRead old = read("99", base + 5);
        old.setEpochMilli(0L);
        stored.add(old);
        long from = base + 1_800_000L;
        long to = base + day + 7_200_000L;

        // Send a few of them live first
        client.addReads(stored.subList(20, 30));
        client.sendQueuedReads();
        requests.clear();

        pendingCommands = new JSONArray().put(new JSONObject().put("id", 9).put("command", "rewind").put("from", from).put("to", to));
        client.pollCommands(client.currentSettings());

        List<Long> expected = stored.stream().map(TagRead::getEpochMilli)
                .filter(e -> e >= from && e < to).sorted().toList();
        assertFalse(expected.isEmpty());
        assertEquals(expected, sentEpochs().stream().sorted().toList());
        assertEquals(1, requests("DELETE", "/commands/pr4-01/9").size());
    }

    @Test
    void aFailedRewindIsNotAcknowledgedAndRunsAgain() throws Exception {
        long base = 1_795_900_000_000L;
        for (int i = 0; i < 10; i++) {
            stored.add(read(Integer.toString(i + 1), base + i * 1000));
        }
        override = r -> r.method().equals("POST") ? new Reply(503, "{\"error\":\"down\"}") : null;
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 5).put("command", "rewind").put("from", base).put("to", base + 60_000));
        client.pollCommands(client.currentSettings());
        assertTrue(requests("DELETE", "/commands/").isEmpty());

        override = r -> null;
        requests.clear();
        client.pollCommands(client.currentSettings());
        assertEquals(10, sentEpochs().size());
        assertEquals(1, requests("DELETE", "/commands/pr4-01/5").size());
    }

    @Test
    void aFailedAcknowledgementIsRetriedWithoutRunningTheCommandAgain() throws Exception {
        override = r -> r.method().equals("DELETE") ? new Reply(503, "{}") : null;
        pendingCommands = new JSONArray().put(new JSONObject().put("id", 1).put("command", "start"));
        client.pollCommands(client.currentSettings());
        reading = false;
        override = r -> null;
        client.pollCommands(client.currentSettings());
        assertEquals(List.of("start"), actionsLog);
        assertEquals(0, pendingCommands.length());
    }

    @Test
    void nothingIsQueuedOrSentWhileOff() throws Exception {
        remote.put("Enabled", false);
        client.reloadSettings();
        assertNull(client.currentSettings());
        client.addReads(reads(5, 1000));
        client.sendQueuedReads();
        assertEquals(0, client.getPendingReads());
        assertTrue(requests.isEmpty());
        assertFalse(client.statusJSON().getBoolean("enabled"));
    }

    @Test
    void turningOffDropsTheQueue() {
        client.addReads(reads(5, 1000));
        assertEquals(5, client.getPendingReads());
        remote.put("Enabled", false);
        client.reloadSettings();
        assertEquals(0, client.getPendingReads());
    }

    @Test
    void aBadUnitIdOrUrlIsReportedAndNothingIsSent() {
        remote.put("URL", "relay.example.com");
        client.reloadSettings();
        assertNull(client.currentSettings());
        assertTrue(client.statusJSON().getString("lastError").contains("URL"));
    }
}
