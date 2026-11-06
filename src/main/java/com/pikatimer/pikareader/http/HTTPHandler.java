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
package com.pikatimer.pikareader.http;

import com.pikatimer.pikareader.conf.PikaConfig;
import com.pikatimer.pikareader.readers.RFIDReader;
import com.pikatimer.pikareader.readers.ReaderHandler;
import com.pikatimer.pikareader.readers.profiles.ProfileManager;
import com.pikatimer.pikareader.readers.profiles.ReaderProfile;
import com.pikatimer.pikareader.status.Status;
import com.pikatimer.pikareader.status.StatusHandler;
import com.pikatimer.pikareader.tags.TagDB;
import com.pikatimer.pikareader.tags.TagRead;
import com.pikatimer.pikareader.tags.TagReadRouter;
import com.pikatimer.pikareader.util.DebugLogHolder;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
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
    private static final List<WsContext> eventWebSocketConnections = new ArrayList<>();
    private static final List<WsContext> statusWebSocketConnections = new ArrayList<>();

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
                tr.setEpochMilli(now.getEpochSecond());
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

            //TODO: Add the ability to specify the from and to 
            // in epoc millis. Need to add the getEPOCMillis to the reads :-) 
            
            // Rewinds the data. "from" and "to" are in ISO_LOCAL_DATE_TIME: 2011-12-03T10:15:30
            javalinApp.get("/rewind/{from}/{to}", ctx -> {
                LocalDateTime fromTime = LocalDateTime.parse(ctx.pathParam("from"));
                LocalDateTime toTime = LocalDateTime.parse(ctx.pathParam("to"));
                ctx.json(buildRewind(fromTime, toTime).toString());
            });

            // Rewinds the data. "from" is in ISO_LOCAL_DATE_TIME: 2011-12-03T10:15:30
            javalinApp.get("/rewind/{from}", ctx -> {
                LocalDateTime fromTime = LocalDateTime.parse(ctx.pathParam("from"));
                ctx.json(buildRewind(fromTime, null).toString());
            });

            javalinApp.get("/rewind", ctx -> {
                JSONArray data = new JSONArray();
                TagDB.getInstance().getReads().stream().filter(Objects::nonNull).sorted().forEach(read -> {
                    data.put(read.toJSONObject());
                });
                ctx.json(data.toString());
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
                profiles.getProfiles().forEach(p -> list.put(p.toJSON()));
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

            // TODO: Reader config page
            // TODO: Uploader config page
            // TODO: Status ws for folks who don't care about the reads
            
            // Start the javalin server
            javalinApp.start(webConfig.optIntegerObject("Port", 8080));

        });

        javalinThread.setDaemon(true);
        javalinThread.setName("JavalinThread");
        javalinThread.start();

        logger.trace("Exiting HTTPHandler constructor");

    }

    private JSONObject currentProfile() {
        ReaderHandler readerHandler = ReaderHandler.getInstance();
        JSONObject response = ProfileManager.getInstance().getCurrent().toJSON();
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
        eventWebSocketConnections.stream().filter(ctx -> ctx.session.isOpen()).forEach(ws -> {
            JSONObject read = tr.toJSONObject();
            read.put("type", "READ");
            ws.send(read.toString());
        });
    }

    public void sendTags(Collection<TagRead> reads) {
        reads.forEach(tr -> {
            sendTag(tr);
        });
    }

    public void postStatus(JSONObject statusReport) {
        statusReport.put("type", "STATUS");
        statusWebSocketConnections.stream().filter(ctx -> ctx.session.isOpen()).forEach(ws -> {
            ws.send(statusReport.toString());
        });
        eventWebSocketConnections.stream().filter(ctx -> ctx.session.isOpen()).forEach(ws -> {
            ws.send(statusReport.toString());
        });
    }


    /**
     * Builds the rewind data for a time range. Today's reads come from memory;
     * anything before the start of today is read from disk.
     *
     * @param fromTime start of range (exclusive)
     * @param toTime end of range (exclusive), or null for open-ended
     */
    private JSONArray buildRewind(LocalDateTime fromTime, LocalDateTime toTime) {
        JSONArray data = new JSONArray();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        // Historical portion: anything before today
        if (fromTime.isBefore(todayStart)) {
            LocalDateTime histTo = (toTime == null || toTime.isAfter(todayStart)) ? todayStart : toTime;
            TagDB.getInstance().getReadsBetween(fromTime, histTo).forEach(read -> data.put(read.toJSONObject()));
        }

        // Today's portion: from memory
        TagDB.getInstance().getReads().stream().filter(Objects::nonNull).sorted().forEach(read -> {
            LocalDateTime ts = read.getTimestamp();
            if (!ts.isBefore(todayStart) && fromTime.isBefore(ts) && (toTime == null || toTime.isAfter(ts))) {
                data.put(read.toJSONObject());
            }
        });
        return data;
    }
}
