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
package org.pikatimer.pikareader.tags;

import org.pikatimer.pikareader.conf.PikaConfig;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class TagDB {

    // Reads not yet written to disk. Unbounded so addReads() never blocks or
    // throws on the router thread, whatever the read rate.
    private static final BlockingQueue<TagRead> pending = new LinkedBlockingQueue<>();
    private static final Logger logger = LoggerFactory.getLogger(TagDB.class);
    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    private final JSONObject dbConfig;
    private final Path dbPath;
    // All of today's reads. Guarded by synchronized (tagList): the DB thread
    // loads it while the router adds to it and HTTP threads copy it.
    private static final List<TagRead> tagList = new ArrayList<>();
    // Serializes file writes between the DB thread and flush()
    private final Object writeLock = new Object();

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("/yyyy/MM/dd/HH/HH-mm-ss.SSS'.dat'");
    private static final DateTimeFormatter dayDirFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter hourDirFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd/HH");

    /**
     * SingletonHolder is loaded on the first execution of
     * Singleton.getInstance() or the first access to SingletonHolder.INSTANCE,
     * not before.
     */
    private static class SingletonHolder {

        private static final TagDB INSTANCE = new TagDB();

    }

    public static TagDB getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private TagDB() {

        // Get the config
        dbConfig = pikaConfig.getKey("DB");

        // if we have an empty config, build out the defaults
        if (dbConfig.isEmpty()) {
            dbConfig.put("Path", System.getProperty("DB", System.getProperty("user.home") + "/.PikaReader")); // Default Gating of 3 seconds
            dbConfig.put("Retention", 180); // 180 days

            pikaConfig.putObject("DB", dbConfig);
        }

        dbPath = Path.of(dbConfig.getString("Path"));

        try {
            Files.createDirectories(dbPath);
        } catch (IOException ex) {
            logger.error("Unable to create database directory {}", dbPath.toAbsolutePath());
        }

        Thread t = new Thread(() -> {

            Instant start = Instant.now();
            // Only load today's reads (local timezone). Older reads are
            // fetched from disk on demand via getReadsBetween()
            Path todayPath = dbPath.resolve(LocalDate.now().format(dayDirFormatter));
            if (Files.isDirectory(todayPath)) {
                try (Stream<Path> walk = Files.walk(todayPath)) {
                    walk.filter(Files::isRegularFile).forEach(f -> {
                        List<TagRead> reads = readFile(f);
                        synchronized (tagList) {
                            tagList.addAll(reads);
                        }
                    });
                } catch (IOException ex) {
                    logger.error("Error reading DB at {}", todayPath.toAbsolutePath().toString(), ex);
                }
            }
            Instant end = Instant.now();
            Duration elapsed = Duration.between(start, end);
            int loaded;
            synchronized (tagList) {
                loaded = tagList.size();
            }
            logger.info("Loaded TagDB in {}ms with {} reads from today", elapsed.toMillis(), loaded);

            // Write the new reads out as they come in. To save on IO and, more
            // importantly, survive a sudden filesystem shutdown when the user
            // pulls the power, each cycle dumps everything not yet written into
            // a single file, so there is at most one file every 5 seconds.
            try {
                while (true) {
                    List<TagRead> batch = new ArrayList<>();
                    batch.add(pending.take()); // block while idle
                    pending.drainTo(batch);
                    try {
                        writeFile(batch);
                    } catch (RuntimeException ex) {
                        logger.error("Error writing {} tag reads to the DB", batch.size(), ex);
                    }
                    // Let the next batch build up for 5 seconds
                    Thread.sleep(5000);
                }
            } catch (InterruptedException ex) {
            }

            logger.info("Exiting TagDBThread");
        });

        t.setDaemon(true);
        t.setName("TagDBThread");
        t.start();

        logger.info("TagDB Processing Thread started.");
    }

    private List<TagRead> readFile(Path f) {
        List<TagRead> reads = new ArrayList<>();
        try (Stream<String> lines = Files.lines(f)) {
            lines.forEach(s -> {
                try {
                    reads.add(new TagRead(new JSONObject(s)));
                } catch (JSONException ex) {
                    logger.error("Error parsing {} in file at {}", s, f.toAbsolutePath().toString());
                }
            });
        } catch (Exception ex) {
            logger.error("Error reading file at {}", f.toAbsolutePath().toString(), ex);
        }
        return reads;
    }

    /**
     * Reads tag reads from the files on disk (not the in-memory list) whose
     * timestamps fall strictly between from and to.
     *
     * @param from start of range (exclusive)
     * @param to end of range (exclusive), or null to read through now
     * @return the matching reads sorted by timestamp
     */
    public List<TagRead> getReadsBetween(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = (to == null) ? LocalDateTime.now() : to;
        List<TagRead> result = new ArrayList<>();

        // Start one hour early since a file is named when it is written, which
        // can be just after the hour rollover for reads taken before it.
        LocalDateTime hour = from.truncatedTo(ChronoUnit.HOURS).minusHours(1);
        while (!hour.isAfter(end)) {
            // skip the whole day if there is no directory for it
            if (!Files.isDirectory(dbPath.resolve(hour.format(dayDirFormatter)))) {
                hour = hour.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            Path hourPath = dbPath.resolve(hour.format(hourDirFormatter));
            if (Files.isDirectory(hourPath)) {
                try (Stream<Path> files = Files.list(hourPath)) {
                    files.filter(Files::isRegularFile).forEach(f -> {
                        for (TagRead r : readFile(f)) {
                            LocalDateTime ts = r.getTimestamp();
                            if (ts != null && from.isBefore(ts) && end.isAfter(ts)) {
                                result.add(r);
                            }
                        }
                    });
                } catch (IOException ex) {
                    logger.error("Error reading DB at {}", hourPath.toAbsolutePath().toString(), ex);
                }
            }
            hour = hour.plusHours(1);
        }
        Collections.sort(result);
        return result;
    }

    public Collection<TagRead> getReads() {
        synchronized (tagList) {
            return new ArrayList<>(tagList);
        }
    }

    /**
     * Records processed reads: they are available from getReads() right away
     * and are written to disk on the DB thread's next cycle (within 5 seconds).
     * Never blocks.
     */
    public void addReads(Collection<TagRead> reads) {
        synchronized (tagList) {
            tagList.addAll(reads);
        }
        pending.addAll(reads);
    }

    /**
     * Writes any reads that are not yet on disk right now. Called at shutdown.
     */
    public void flush() {
        List<TagRead> batch = new ArrayList<>();
        pending.drainTo(batch);
        if (!batch.isEmpty()) {
            writeFile(batch);
            logger.info("Flushed {} tag reads to the DB", batch.size());
        }
    }

    // Writes the reads to a new file at <dbpath>/YYYY/MM/DD/HH/HH-mm-ss.SSS.dat
    private void writeFile(List<TagRead> reads) {
        StringBuilder data = new StringBuilder();
        reads.forEach(read -> data.append(read.toJSON()).append(System.lineSeparator()));

        synchronized (writeLock) {
            File outputFile = new File(dbPath.toString() + LocalDateTime.now().format(formatter));
            // Two writes in the same millisecond (flush() racing the DB thread)
            // must not overwrite each other
            while (outputFile.exists()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                outputFile = new File(dbPath.toString() + LocalDateTime.now().format(formatter));
            }

            logger.debug("Writing {} tag reads to {}", reads.size(), outputFile.getAbsolutePath());

            // Make the parent path
            outputFile.getParentFile().mkdirs();

            try (FileWriter output = new FileWriter(outputFile)) {
                output.write(data.toString());
            } catch (IOException ex) {
                logger.error("Error writing to {}", outputFile.getAbsolutePath(), ex);
            }
        }
    }

}
