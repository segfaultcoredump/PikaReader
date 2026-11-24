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

import org.pikatimer.pikareader.conf.HostTimezone;
import org.pikatimer.pikareader.conf.PikaConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
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

    // The zone the DB folders are named in: the host's own timezone, which
    // can differ from the configured Timezone that read timestamps use. Not
    // ZoneId.systemDefault(): a Timezone other than AUTO replaces that, and
    // the folders already written would then be read as the wrong hours.
    private final ZoneId dirZone = HostTimezone.hostZone();
    // Start of the startup day (epoch ms). Every read with epochMilli from
    // here on is in tagList: today's files are loaded at startup and every
    // read since is added. Rewinds take older reads from disk.
    private final long memoryFrom;
    // Counted down once today's files are in tagList
    private final CountDownLatch loaded = new CountDownLatch(1);
    private static final long LOAD_WAIT_SECONDS = 60;

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("/yyyy/MM/dd/HH/HH-mm-ss.SSS'.dat'");
    private static final DateTimeFormatter dayDirFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd");

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

        // fill in any defaults that are missing
        if (!dbConfig.has("Path") || !dbConfig.has("Retention")) {
            if (!dbConfig.has("Path")) {
                dbConfig.put("Path", System.getProperty("DB", System.getProperty("user.home") + "/.PikaReader"));
            }
            if (!dbConfig.has("Retention")) {
                dbConfig.put("Retention", 180); // 180 days
            }
            pikaConfig.putObject("DB", dbConfig);
        }

        dbPath = Path.of(dbConfig.getString("Path"));

        try {
            Files.createDirectories(dbPath);
        } catch (IOException ex) {
            logger.error("Unable to create database directory {}", dbPath.toAbsolutePath());
        }

        LocalDate today = LocalDate.now(dirZone);
        memoryFrom = today.atStartOfDay(dirZone).toInstant().toEpochMilli();

        Thread t = new Thread(() -> {

            Instant start = Instant.now();
            // Only load today's reads (the folders' timezone). Older reads
            // are fetched from disk on demand for a rewind
            Path todayPath = dbPath.resolve(today.format(dayDirFormatter));
            try {
                if (Files.isDirectory(todayPath)) {
                    try (Stream<Path> walk = Files.walk(todayPath)) {
                        walk.filter(Files::isRegularFile).sorted().forEach(f -> {
                            List<TagRead> reads = TagFiles.readFile(f);
                            synchronized (tagList) {
                                tagList.addAll(reads);
                            }
                        });
                    } catch (IOException ex) {
                        logger.error("Error reading DB at {}", todayPath.toAbsolutePath().toString(), ex);
                    }
                }
            } finally {
                loaded.countDown();
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

    /**
     * The reads with epochMilli in [fromMs, toMs), sorted by time. Exact
     * whatever the local time did in between (daylight saving, a changed
     * Timezone setting). Reads from the startup day on come from memory, older
     * ones from disk.
     *
     * @param fromMs start of range, epoch ms (inclusive)
     * @param toMs end of range, epoch ms (exclusive); Long.MAX_VALUE for no end
     */
    public List<TagRead> getReadsBetween(long fromMs, long toMs) {
        awaitLoaded();
        return TagFiles.between(dbPath, dirZone, memoryFrom, this::forEachInMemory, fromMs, toMs);
    }

    /**
     * The reads whose timestamp label (the local time in the configured
     * Timezone when each was read) is strictly between from and to, sorted
     * by time. Use getReadsBetween(long, long) for an exact range.
     *
     * @param fromTime start of range (exclusive)
     * @param toTime end of range (exclusive), or null for open-ended
     */
    public List<TagRead> getRewindReads(LocalDateTime fromTime, LocalDateTime toTime) {
        awaitLoaded();
        return TagFiles.betweenLabels(dbPath, dirZone, memoryFrom, this::forEachInMemory,
                fromTime, toTime, pikaConfig.getTimezoneId(), System.currentTimeMillis());
    }

    private void forEachInMemory(Consumer<TagRead> action) {
        synchronized (tagList) {
            tagList.forEach(action);
        }
    }

    // A rewind right after startup must not miss today's reads still loading
    private void awaitLoaded() {
        try {
            if (!loaded.await(LOAD_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Today's reads are still loading; the rewind may be missing some");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
            File outputFile = new File(dbPath.toString() + LocalDateTime.now(dirZone).format(formatter));
            // Two writes in the same millisecond (flush() racing the DB thread)
            // must not overwrite each other
            while (outputFile.exists()) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                outputFile = new File(dbPath.toString() + LocalDateTime.now(dirZone).format(formatter));
            }

            logger.trace("Writing {} tag reads to {}", reads.size(), outputFile.getAbsolutePath());

            // Make the parent path
            File parent = outputFile.getParentFile();
            boolean newDir = !parent.isDirectory();
            parent.mkdirs();

            // Force the data to the disk, not just the page cache, so the
            // reads survive the power being pulled right after the write
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (IOException ex) {
                logger.error("Error writing to {}", outputFile.getAbsolutePath(), ex);
            }

            // The file's directory entry has to be on disk too, and a new
            // hour directory needs its own entry in the day directory
            syncDirectory(parent.toPath());
            if (newDir) {
                for (Path p = parent.toPath().getParent(); p != null && p.startsWith(dbPath); p = p.getParent()) {
                    syncDirectory(p);
                }
            }
        }
    }

    // fsync a directory. Works on Linux; some platforms can't open a
    // directory for reading, which only costs the extra durability there.
    private static void syncDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ex) {
            logger.trace("Unable to sync directory {}", dir, ex);
        }
    }

}
