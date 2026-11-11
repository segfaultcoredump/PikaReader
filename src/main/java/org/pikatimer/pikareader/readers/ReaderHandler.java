/*
 * Copyright (C) 2023 John Garner <segfaultcoredump@gmail.com>
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
package org.pikatimer.pikareader.readers;

import org.pikatimer.pikareader.conf.PikaConfig;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.readers.profiles.ReaderProfile;
import org.pikatimer.pikareader.status.StatusHandler;
import org.pikatimer.pikareader.tags.ReadFilterSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ReaderHandler {

    private static final Logger logger = LoggerFactory.getLogger(ReaderHandler.class);

    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    // Filled concurrently by the virtual threads that create each reader
    private static final Map<Integer, RFIDReader> readers = new ConcurrentHashMap<>();
    private static final Map<String, RFIDReader> rfidReaderFactory = new HashMap<>();

    private final JSONObject readerConfig;

    private volatile Boolean isReading = false;

    /**
     * SingletonHolder is loaded on the first execution of
     * Singleton.getInstance() or the first access to SingletonHolder.INSTANCE,
     * not before.
     */
    private static class SingletonHolder {

        private static final ReaderHandler INSTANCE = new ReaderHandler();

    }

    public static ReaderHandler getInstance() {

        return SingletonHolder.INSTANCE;
    }

    private ReaderHandler() {
        // get the config and build the hierarchy
        readerConfig = pikaConfig.getKey("Reader");

        // We will use a service loader to make it easeir for somebody to add
        // a new reader type by just wiring up their own RFIDReader implementation
        // and attaching a jar file. 
        ServiceLoader<RFIDReader> serviceLoader = ServiceLoader.load(RFIDReader.class);

        for (RFIDReader service : serviceLoader) {
            logger.info("Loaded {} RFIDReader handler.", service.getType());
            rfidReaderFactory.put(service.getType(), service);
        }

        logger.info("Found " + rfidReaderFactory.size() + " services!");

        // if we have an empty config, build out the defaults
        if (readerConfig.isEmpty()) {
            readerConfig.put("Gating Style", ReaderGatingStyle.READER.getStyle());
            JSONArray defaultReaders = new JSONArray();
            JSONObject defaultReader = new JSONObject();
            defaultReader.put("Index", 1);
            defaultReader.put("Type", "IMPINJ");
            defaultReader.put("IP", "192.0.2.10");
            defaultReader.put("Power Level", "HIGH");
            defaultReaders.put(defaultReader);

            readerConfig.put("Readers", defaultReaders);

            pikaConfig.putObject("Reader", readerConfig);
        }

        // Each profile now sets its own read filter
        if (readerConfig.has("Gating")) {
            logger.warn("\"Gating\" in the \"Reader\" config is no longer used: each reader profile sets its own read filter. "
                    + "To change the Finish Mats window, set \"{}\" (seconds) instead.", ReadFilterSettings.FINISH_MATS_GATING);
        }
        ReaderProfile profile = ProfileManager.getInstance().getCurrent();
        logger.info("Read filter for the {} profile: {}", profile, ReadFilterSettings.forProfile(profile, readerConfig).summary());

        // Find the readers we can set up
        Set<Integer> seenIndexes = new HashSet<>();
        List<JSONObject> validReaders = new ArrayList<>();
        readerConfig.getJSONArray("Readers").forEach(r -> {
            JSONObject rc = (JSONObject) r; // FFS
            Integer index = rc.getInt("Index");
            String type = rc.optString("Type", "NOT SET");

            if (!seenIndexes.add(index)) {
                logger.error("RFID Reader Config Error! More than one reader uses Index {}. Each reader needs a unique Index. Ignoring the duplicate {} reader.", index, type);
            } else if (rfidReaderFactory.containsKey(type)) {
                validReaders.add(rc);
            } else {
                logger.error("RFID Reader Config Error! No handler found for RFID Reader type {}", type);
            }
        });

        // Load the selected profile and give each reader its session before
        // the readers start connecting
        ProfileManager.getInstance().assignSessions(validReaders.stream().map(rc -> rc.getInt("Index")).sorted().toList());

        // itterate through the readers and set them up
        CountDownLatch latch = new CountDownLatch(validReaders.size());
        validReaders.forEach(rc -> {
            Thread.startVirtualThread(() -> {
                try {
                    RFIDReader reader = rfidReaderFactory.get(rc.getString("Type")).create(rc);
                    readers.put(rc.getInt("Index"), reader);
                } finally {
                    latch.countDown();
                }
            });
        });
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
        }

    }

    public void setClocks() {
        readers.values().stream().parallel().forEach(r -> r.setClock());
    }

    public Boolean isReading() {
        return isReading;
    }

    public synchronized void startReading() {
        CountDownLatch latch = new CountDownLatch(readers.size());

        // Start all readers in parallel. 
        // A parallelStream() is a suggestion but this guarantees it
        logger.info("Starting Readers");
        readers.values().stream().forEach(r -> {
            Thread.startVirtualThread(() -> {
                r.startReading();
                if (r.isReading())
                    latch.countDown();
                else {
                    r.startReading();
                    latch.countDown();
                }
            });
        });
        try {
            latch.await(30, TimeUnit.SECONDS);

        } catch (InterruptedException ex) {
        }
        isReading = true;
        StatusHandler.getInstance().clearReadCount();

        logger.info("Readers Started");

    }

    public synchronized void stopReading() {
        logger.info("Stopping Readers");
        CountDownLatch latch = new CountDownLatch(readers.size());
        readers.values().parallelStream().forEach(r -> {
            r.stopReading();
            latch.countDown();
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
        }
        isReading = false;
        logger.info("Readers Stopped");
    }

    public Collection<RFIDReader> getReaders() {
        return readers.values();
    }

    /**
     * Select a reader profile, save it in the config and re-apply every
     * reader's settings. Only allowed while the readers are not reading.
     * Readers that aren't connected pick it up when they connect.
     *
     * @param name the profile id or display name
     * @return the selected profile
     * @throws IllegalArgumentException for an unknown profile
     * @throws IllegalStateException while reading
     */
    public synchronized ReaderProfile setProfile(String name) {
        ProfileManager profiles = ProfileManager.getInstance();
        ReaderProfile profile = profiles.find(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown reader profile '" + name + "'"));
        if (isReading) {
            throw new IllegalStateException("Stop reading before changing the reader profile");
        }
        if (profile.equals(profiles.getCurrent())) {
            logger.info("Reader profile is already {}", profile);
            return profile;
        }

        profiles.setCurrent(profile);

        logger.info("Applying the {} profile to the readers", profile);
        CountDownLatch latch = new CountDownLatch(readers.size());
        readers.values().forEach(r -> {
            Thread.startVirtualThread(() -> {
                try {
                    r.applyProfile();
                } catch (Exception ex) {
                    logger.error("Unable to apply the {} profile to reader {}", profile, r.getID(), ex);
                } finally {
                    latch.countDown();
                }
            });
        });
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                logger.warn("Timed out waiting for the readers to apply the {} profile", profile);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        logger.info("Reader profile {} applied", profile);
        return profile;
    }

}
