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
package org.pikatimer.pikareader.http;

import org.pikatimer.pikareader.conf.BasicSettings;
import org.pikatimer.pikareader.conf.PikaConfig;
import org.pikatimer.pikareader.conf.RemoteSettings;
import org.pikatimer.pikareader.readers.RFIDReader;
import org.pikatimer.pikareader.readers.ReaderHandler;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.readers.profiles.ReaderProfile;
import org.pikatimer.pikareader.relay.RelayClient;
import org.pikatimer.pikareader.status.Status;
import org.pikatimer.pikareader.status.StatusHandler;
import org.pikatimer.pikareader.tags.ReadFilterSettings;
import org.pikatimer.pikareader.tags.TagDB;
import org.pikatimer.pikareader.tags.TagRead;
import org.pikatimer.pikareader.tags.TagReadRouter;
import org.pikatimer.pikareader.util.DebugLogHolder;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class HTTPHandler {

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd' 'HH:mm:ss.SSS");

    private static final Logger logger = LoggerFactory.getLogger(HTTPHandler.class);

    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    private final JSONObject webConfig;

    private Thread javalinThread;
    private Javalin javalinApp;
    // Jetty threads add and remove clients while the router and status
    // threads send to them, so these must be safe to iterate concurrently
    private static final List<WsContext> eventWebSocketConnections = new CopyOnWriteArrayList<>();
    private static final List<WsContext> statusWebSocketConnections = new CopyOnWriteArrayList<>();

    // How many messages may queue up for one websocket client before it is
    // dropped as too slow. Several minutes of reads at a busy start, and at
    // most a few MB. The client reconnects and rewinds.
    private static final int MAX_OUTGOING_FRAMES = 10000;

    // Web server threads. Websockets don't hold a thread, so a small pool is
    // plenty and keeps the thread stacks down on a Pi. (Not virtual threads:
    // on Java 21 a virtual thread blocked on a synchronized ReaderHandler
    // method pins one of the few carrier threads.)
    private static final int HTTP_MAX_THREADS = 32;
    private static final int HTTP_MIN_THREADS = 4;

    // A /rewind time given as epoch milliseconds (up to 18 digits fits a long)
    private static final Pattern EPOCH_MS = Pattern.compile("\\d{1,18}");

//    ReaderHandler readerHandler = ReaderHandler.getInstance();
    private static class SingletonHolder {

        private static final HTTPHandler INSTANCE = new HTTPHandler();

    }

    public static HTTPHandler getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private HTTPHandler() {
        logger.trace("Starting HTTPHandler constructor");
        // get the config and build the hierarchy
        webConfig = pikaConfig.getKey("Web");

        // if we have an empty config, build out the defaults
        if (webConfig.isEmpty()) {
            webConfig.put("Port", 8080); // Default Gating of 3 seconds
            pikaConfig.putObject("Web", webConfig);
        }

        logger.info("Starting Javalin on port {}", webConfig.optIntegerObject("Port", 8080));

        javalinThread = new Thread(() -> {
            javalinApp = Javalin.create(config -> {
                config.staticFiles.add("/public", Location.CLASSPATH);

                QueuedThreadPool threadPool = new QueuedThreadPool(HTTP_MAX_THREADS, HTTP_MIN_THREADS, 60000);
                threadPool.setName("JettyServerThreadPool");
                config.jetty.threadPool = threadPool;
                
                config.jetty.modifyWebSocketServletFactory(factory -> {
                    // Set idle timeout to 15 seconds. 
                    // If no data or pings are exchanged for 15s, the server drops the socket.
                    factory.setIdleTimeout(Duration.ofSeconds(15));
                });
                
            });

            // WebSocket to send all events along
            javalinApp.ws("/events", ws -> {
                ws.onConnect(ctx -> {
                    ctx.enableAutomaticPings();
                    ctx.session.getRemote().setMaxOutgoingFrames(MAX_OUTGOING_FRAMES);
                    eventWebSocketConnections.add(ctx);
                    
                    logger.debug("/event Websocket onConnect: {}",ctx.session.getRemoteAddress().toString());

                    //stash the remote IP for later use if we need it
                    var remoteAddr = ctx.session.getRemoteAddress();
                    String ipAddress = (remoteAddr != null) ? remoteAddr.toString() : "Unknown IP";
                    ctx.attribute("user-ip", ipAddress);
                });
                ws.onClose(ctx -> {
                    eventWebSocketConnections.remove(ctx);
                    String ipAddress = ctx.attribute("user-ip");
                    logger.debug("/event Websocket onClose from {} due to {}", ipAddress, ctx.closeStatus().toString());
                });
                ws.onMessage(ctx -> {

                });
            });
            
            javalinApp.ws("/status", ws -> {
                ws.onConnect(ctx -> {
                    logger.debug("/status Websocket onConnect: {}",ctx.session.getRemoteAddress().toString());
                    ctx.enableAutomaticPings();
                    ctx.session.getRemote().setMaxOutgoingFrames(MAX_OUTGOING_FRAMES);
                    statusWebSocketConnections.add(ctx);
                    
                    //stash the remote IP for later use if we need it
                    var remoteAddr = ctx.session.getRemoteAddress();
                    String ipAddress = (remoteAddr != null) ? remoteAddr.toString() : "Unknown IP";
                    ctx.attribute("user-ip", ipAddress);
                });
                ws.onClose(ctx -> {
                    statusWebSocketConnections.remove(ctx);
                    String ipAddress = ctx.attribute("user-ip");
                    logger.debug("/event Websocket onClose due to {}", ctx.closeStatus().toString());
                    
                });
                ws.onMessage(ctx -> {

                });
            });

            // Stop Readers
            javalinApp.get("/start", ctx -> {
                logger.info("HTTPD Request: /start -> Starting Readers...");
                ReaderHandler.getInstance().startReading();
                JSONObject response = new JSONObject();
                response.put("readers", "started");
                response.put("timestamp", LocalDateTime.now(PikaConfig.getInstance().getTimezoneId()).format(formatter));
                ctx.json(response.toString());
            });

            // Start Readers
            javalinApp.get("/stop", ctx -> {
                logger.info("HTTPD Request: /stop -> Stopping Readers...");
                ReaderHandler.getInstance().stopReading();
                JSONObject response = new JSONObject();
                response.put("readers", "stopped");
                response.put("timestamp", LocalDateTime.now(PikaConfig.getInstance().getTimezoneId()).format(formatter));
                ctx.json(response.toString());
            });

            // debug info
            javalinApp.get("/debug", ctx -> {
                StringBuilder response = new StringBuilder();
                response.append("<html><head><Title>PikaReader Debug</title></head><body><pre>\n");
                DebugLogHolder.getEventList().forEach(event -> {
                    //ILoggingEvent event = DebugLogHolder.eventMap.get(e);
                    response.append(event.getInstant()).append(": ");
                    response.append(event.getLevel().toString()).append(": ");
                    response.append(event.getLoggerName()).append(": ");
                    response.append(event.getFormattedMessage());
                    //if (event.hasCallerData()) response.append(event.getCallerData());
                    response.append("<BR>\n");
                });
                response.append("</pre><body><html>\n");
                ctx.html(response.toString());
            });

            // Trigger
            javalinApp.get("/trigger", ctx -> {
                Instant now = Instant.now();
                TagRead tr = new TagRead();
                tr.setEPC("0");
                tr.setEpochMilli(now.toEpochMilli());
                tr.setTimestamp(LocalDateTime.now(PikaConfig.getInstance().getTimezoneId()));
                tr.setTZOffset(ZonedDateTime.ofInstant(now, PikaConfig.getInstance().getTimezoneId()).getOffset().toString());
                tr.setReaderID(0);
                tr.setReaderAntenna(0);
                tr.setPeakRSSI(0.0);
                List<TagRead> trigger = new ArrayList<>();
                trigger.add(tr);
                TagReadRouter.getInstance().routeTagReads(trigger);
                ctx.json(tr.toJSON());
            });

            // Rewinds the data. "from" and "to" are either epoch milliseconds
            // (an exact range, [from, to)) or ISO_LOCAL_DATE_TIME such as
            // 2011-12-03T10:15:30 (matched against each read's local time
            // label, from and to both exclusive)
            javalinApp.get("/rewind/{from}/{to}", ctx -> {
                rewind(ctx, ctx.pathParam("from"), ctx.pathParam("to"));
            });

            // Rewinds the data from "from" (epoch ms or ISO_LOCAL_DATE_TIME) on
            javalinApp.get("/rewind/{from}", ctx -> {
                rewind(ctx, ctx.pathParam("from"), null);
            });

            javalinApp.get("/rewind", ctx -> {
                writeReads(ctx, TagDB.getInstance().getReads().stream().filter(Objects::nonNull).sorted().toList());
            });

            // TODO: Status Page
            javalinApp.get("/status", ctx -> {
                ctx.json(StatusHandler.getInstance().getStatus().toString(4));
            });

            // Reader profiles: the ones available and the current one
            javalinApp.get("/profiles", ctx -> {
                ProfileManager profiles = ProfileManager.getInstance();
                JSONObject response = new JSONObject();
                response.put("current", profiles.getCurrent().id());
                JSONArray list = new JSONArray();
                JSONObject readerConfig = PikaConfig.getInstance().getKey("Reader");
                profiles.getProfiles().forEach(p -> list.put(p.toJSON()
                        .put("readFilter", ReadFilterSettings.forProfile(p, readerConfig).summary())));
                response.put("profiles", list);
                ctx.json(response.toString());
            });

            // The current profile and the settings each reader is using for it
            javalinApp.get("/profile", ctx -> {
                ctx.json(currentProfile().toString());
            });

            // Change the profile. Only allowed while the readers are not reading.
            javalinApp.put("/profile/{profile}", ctx -> {
                String name = ctx.pathParam("profile");
                logger.info("HTTPD Request: PUT /profile/{}", name);
                try {
                    ReaderHandler.getInstance().setProfile(name);
                    JSONObject response = currentProfile();
                    response.put("timestamp", LocalDateTime.now(PikaConfig.getInstance().getTimezoneId()).format(formatter));
                    ctx.json(response.toString());
                } catch (IllegalArgumentException ex) {
                    JSONObject response = new JSONObject();
                    response.put("error", ex.getMessage());
                    response.put("profiles", ProfileManager.getInstance().getProfiles().stream().map(ReaderProfile::id).toList());
                    ctx.status(404).json(response.toString());
                } catch (IllegalStateException ex) {
                    JSONObject response = new JSONObject();
                    response.put("error", ex.getMessage());
                    response.put("current", ProfileManager.getInstance().getCurrent().id());
                    ctx.status(409).json(response.toString());
                }
            });

            // The basic settings for the settings page (/settings/), and the
            // choices for each one
            javalinApp.get("/config", ctx -> {
                ctx.json(configResponse().toString());
            });

            // Save the basic settings. They take effect when PikaReader
            // restarts. Only allowed while the readers are not reading.
            javalinApp.put("/config", ctx -> {
                logger.info("HTTPD Request: PUT /config");
                ReaderHandler readerHandler = ReaderHandler.getInstance();
                JSONObject response = new JSONObject();
                if (readerHandler.isReading()) {
                    response.put("errors", new JSONArray().put("Stop the reader before changing the settings"));
                    ctx.status(409).json(response.toString());
                    return;
                }
                JSONObject settings;
                try {
                    settings = new JSONObject(ctx.body());
                } catch (JSONException ex) {
                    response.put("errors", new JSONArray().put("The settings aren't valid JSON: " + ex.getMessage()));
                    ctx.status(400).json(response.toString());
                    return;
                }
                List<String> errors = BasicSettings.validate(settings, BasicSettings.read(pikaConfig), readerHandler.getReaderTypes(),
                        RemoteSettings.isEnabled(remoteRoot()));
                if (!errors.isEmpty()) {
                    logger.info("Settings not saved: {}", errors);
                    response.put("errors", errors);
                    ctx.status(400).json(response.toString());
                    return;
                }
                pikaConfig.edit(root -> BasicSettings.apply(root, settings));
                logger.info("Settings saved from the web UI. Restart PikaReader to apply them.");
                response = configResponse();
                response.put("saved", true);
                response.put("timestamp", LocalDateTime.now(pikaConfig.getTimezoneId()).format(formatter));
                ctx.json(response.toString());
            });

            // Save the PikaRelay settings. They take effect right away and
            // may be changed while reading.
            javalinApp.put("/config/remote", ctx -> {
                logger.info("HTTPD Request: PUT /config/remote");
                JSONObject response = new JSONObject();
                JSONObject remote;
                try {
                    remote = new JSONObject(ctx.body());
                } catch (JSONException ex) {
                    response.put("errors", new JSONArray().put("The settings aren't valid JSON: " + ex.getMessage()));
                    ctx.status(400).json(response.toString());
                    return;
                }
                List<String> errors = RemoteSettings.validate(remote, RemoteSettings.read(pikaConfig), pikaConfig.getStringValue("UnitID"));
                if (!errors.isEmpty()) {
                    logger.info("PikaRelay settings not saved: {}", errors);
                    response.put("errors", errors);
                    ctx.status(400).json(response.toString());
                    return;
                }
                pikaConfig.editLive(root -> RemoteSettings.apply(root, remote));
                RelayClient relay = RelayClient.getInstance();
                relay.reloadSettings();
                logger.info("PikaRelay settings saved from the web UI");
                response.put("remote", RemoteSettings.read(pikaConfig));
                response.put("remoteStatus", relay.statusJSON());
                response.put("saved", true);
                response.put("timestamp", LocalDateTime.now(pikaConfig.getTimezoneId()).format(formatter));
                ctx.json(response.toString());
            });

            // TODO: Status ws for folks who don't care about the reads
            
            // Start the javalin server
            javalinApp.start(webConfig.optIntegerObject("Port", 8080));

        });

        javalinThread.setDaemon(true);
        javalinThread.setName("JavalinThread");
        javalinThread.start();

        logger.trace("Exiting HTTPHandler constructor");

    }

    private JSONObject configResponse() {
        JSONObject response = new JSONObject();
        response.put("settings", BasicSettings.read(pikaConfig));
        response.put("readerTypes", ReaderHandler.getInstance().getReaderTypes().stream().sorted().toList());
        response.put("powerLevels", BasicSettings.powerLevels());
        response.put("clocks", BasicSettings.CLOCKS);
        response.put("chipFormats", BasicSettings.CHIP_FORMATS);
        response.put("regions", BasicSettings.regions());
        response.put("gatingStyles", BasicSettings.gatingStyles());
        response.put("reading", ReaderHandler.getInstance().isReading());
        response.put("restartPending", pikaConfig.isRestartPending());
        response.put("remote", RemoteSettings.read(pikaConfig));
        response.put("remoteStatus", RelayClient.getInstance().statusJSON());
        return response;
    }

    /** The config's [Remote] section, as a root object for RemoteSettings. */
    private static JSONObject remoteRoot() {
        return new JSONObject().put(RemoteSettings.REMOTE, pikaConfig.getKeyCopy(RemoteSettings.REMOTE));
    }

    private JSONObject currentProfile() {
        ReaderHandler readerHandler = ReaderHandler.getInstance();
        ReaderProfile profile = ProfileManager.getInstance().getCurrent();
        JSONObject response = profile.toJSON();
        response.put("readFilter", ReadFilterSettings.forProfile(profile, PikaConfig.getInstance().getKey("Reader")).summary());
        response.put("reading", readerHandler.isReading());
        JSONArray readers = new JSONArray();
        readerHandler.getReaders().stream().sorted(Comparator.comparing(RFIDReader::getID)).forEach(r -> {
            JSONObject reader = new JSONObject();
            reader.put("id", r.getID());
            reader.put("type", r.getType());
            reader.put("status", r.getStatus());
            reader.put("settings", r.getProfileSettings());
            readers.put(reader);
        });
        response.put("readers", readers);
        return response;
    }

    public void stopHTTPD() {
        javalinApp.stop();
    }

    public void sendTag(TagRead tr) {
        if (eventWebSocketConnections.isEmpty()) {
            return;
        }
        JSONObject read = tr.toJSONObject();
        read.put("type", "READ");
        String message = read.toString();
        eventWebSocketConnections.forEach(ws -> send(ws, message));
    }

    public void sendTags(Collection<TagRead> reads) {
        reads.forEach(tr -> {
            sendTag(tr);
        });
    }

    public void postStatus(JSONObject statusReport) {
        statusReport.put("type", "STATUS");
        if (statusWebSocketConnections.isEmpty() && eventWebSocketConnections.isEmpty()) {
            return;
        }
        String message = statusReport.toString();
        statusWebSocketConnections.forEach(ws -> send(ws, message));
        eventWebSocketConnections.forEach(ws -> send(ws, message));
    }

    /**
     * Queues a message for a websocket client without waiting for it to be
     * sent, so one slow client can't hold up the reads for everyone else.
     * Jetty sends each client's messages in order. A client whose backlog hits
     * MAX_OUTGOING_FRAMES, or whose send fails, is dropped; it can reconnect
     * and rewind.
     */
    private void send(WsContext ws, String message) {
        if (!ws.session.isOpen()) {
            return;
        }
        ws.session.getRemote().sendString(message, new WriteCallback() {
            @Override
            public void writeFailed(Throwable x) {
                // Only the first failure for a client gets here with it
                // still in a list, so it's logged and closed once
                boolean removed = eventWebSocketConnections.remove(ws);
                removed |= statusWebSocketConnections.remove(ws);
                if (removed) {
                    logger.warn("Dropping websocket client {}: unable to send to it ({})", (Object) ws.attribute("user-ip"), x.toString());
                    try {
                        ws.session.close(StatusCode.TRY_AGAIN_LATER, "Client too slow");
                    } catch (RuntimeException ex) {
                        logger.debug("Error closing websocket client {}", (Object) ws.attribute("user-ip"), ex);
                    }
                }
            }
        });
    }


    /**
     * Answer a /rewind/{from}[/{to}]. Digits only means epoch milliseconds,
     * for [from, to); anything else is an ISO local date-time, matched
     * against the reads' local time labels as before. Both must use the same
     * form. Bad input gets a 400.
     */
    private static void rewind(Context ctx, String from, String to) throws IOException {
        boolean epoch = EPOCH_MS.matcher(from).matches();
        if (to != null && epoch != EPOCH_MS.matcher(to).matches()) {
            rewindError(ctx, "Use the same form for from and to: both epoch milliseconds or both local date-times such as 2026-11-24T08:00:00");
            return;
        }
        if (epoch) {
            long fromMs = Long.parseLong(from);
            long toMs = to == null ? Long.MAX_VALUE : Long.parseLong(to);
            if (toMs <= fromMs) {
                rewindError(ctx, "The end of the range (" + to + ") must be after the start (" + from + ")");
                return;
            }
            writeReads(ctx, TagDB.getInstance().getReadsBetween(fromMs, toMs));
            return;
        }
        LocalDateTime fromTime;
        LocalDateTime toTime;
        try {
            fromTime = LocalDateTime.parse(from);
            toTime = to == null ? null : LocalDateTime.parse(to);
        } catch (DateTimeParseException ex) {
            rewindError(ctx, "Unable to read the time '" + ex.getParsedString() + "'. Use epoch milliseconds or a local date-time such as 2026-11-24T08:00:00");
            return;
        }
        writeReads(ctx, TagDB.getInstance().getRewindReads(fromTime, toTime));
    }

    private static void rewindError(Context ctx, String message) {
        logger.info("Bad rewind request {}: {}", ctx.path(), message);
        ctx.status(400).json(new JSONObject().put("error", message).toString());
    }

    /**
     * Streams the reads to the client as a JSON array, one read at a time,
     * instead of building the whole array in memory first.
     */
    private static void writeReads(Context ctx, List<TagRead> reads) throws IOException {
        ctx.contentType(ContentType.APPLICATION_JSON);
        Writer out = new BufferedWriter(new OutputStreamWriter(ctx.outputStream(), StandardCharsets.UTF_8));
        TagRead.writeJSONArray(reads, out);
        out.flush();
    }
}
