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
package com.pikatimer.pikareader.tags;

import com.pikatimer.pikareader.conf.PikaConfig;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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

    private static final BlockingQueue<Collection<TagRead>> tagQueue = new ArrayBlockingQueue<>(100);
    private static final Logger logger = LoggerFactory.getLogger(TagDB.class);
    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    private final JSONObject dbConfig;
    private final Path dbPath;
    private static final List<TagRead> tagList = new ArrayList<>();

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
                    walk.filter(Files::isRegularFile).forEach(f -> tagList.addAll(readFile(f)));
                } catch (IOException ex) {
                    logger.error("Error reading DB at {}", todayPath.toAbsolutePath().toString(), ex);
                }
            }
            Instant end = Instant.now();
            Duration elapsed = Duration.between(start, end);
            logger.info("Loaded TagDB in {}ms with {} reads from today", elapsed.toMillis(), tagList.size());

            // Setup loop to add new tag reads to a new file as they come in. 
            // To save on IO and more importantly, survive a sudden filesystem shutdown when the 
            // user pulls the power, each cycle will dump all of the tags into a single file. 
            // generally speaking, this will produce no more than 1 file per gating perod. 
            try {
                while (true) {
                    Collection<TagRead> tr = tagQueue.take();

                    // Add all of the tags to our list
                    tagList.addAll(tr);

                    StringBuilder data = new StringBuilder();
                    tr.forEach(read -> {
                        data.append(read.toJSON()).append(System.lineSeparator());
                    });

                    // target file of <dbbapth>/YYYY/MM/DD/HH/HH-MM-ss.SSS.dat 
                    File outputFile = new File(dbPath.toString() + LocalDateTime.now().format(formatter));

                    logger.debug("Writing to data to {}", outputFile.getAbsolutePath());

                    // Make the parent path
                    outputFile.getParentFile().mkdirs();

                    try (FileWriter output = new FileWriter(outputFile);) {
                        output.write(data.toString());
                    } catch (IOException ex) {
                        logger.error("Error writing to {}", outputFile.getAbsolutePath(), ex);
                    }
                    // Take a nap so that we don't write out a dozen files per second
                    // This limits us to no more than one file every 5 seconds or so
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
        return new ArrayList<>(tagList);
    }

    public void addReads(Collection<TagRead> reads) {
        tagQueue.add(reads);

    }

}
