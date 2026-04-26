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
package com.pikatimer.pikareader.tags;

import com.pikatimer.pikareader.conf.PikaConfig;
import com.pikatimer.pikareader.readers.ReaderGatingStyle;
import com.pikatimer.pikareader.status.StatusHandler;
import java.awt.Toolkit;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class TagReadProcessor implements Runnable {

    private static final BlockingQueue<TagRead> tagQueue = new ArrayBlockingQueue<>(100000);
    private static final Logger logger = LoggerFactory.getLogger(TagReadProcessor.class);
    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    private static final TagReadRouter tagRouter = TagReadRouter.getInstance();
    private static final StatusHandler statusHandler = StatusHandler.getInstance();

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd' 'HH:mm:ss.SSS");

    Map<String, TagRead> seenTags = new HashMap<>();
    Integer defaultGating = 3000;

    private Thread tagProcessingThread;

    /**
     * SingletonHolder is loaded on the first execution of
     * Singleton.getInstance() or the first access to SingletonHolder.INSTANCE,
     * not before.
     */
    private static class SingletonHolder {

        private static final TagReadProcessor INSTANCE = new TagReadProcessor();

    }

    public static TagReadProcessor getInstance() {
        return SingletonHolder.INSTANCE;
    }

    @Override
    public void run() {
        Map<String,List<TagRead>> tagReadMap = new HashMap<>(1000);

        try {
            while (true) {
                                
                List<TagRead> tags = new ArrayList<>();

                Integer gating = pikaConfig.getKey("Reader").optIntegerObject("Gating") * 1000;
                if (gating.equals(0)) {
                    gating = defaultGating;
                }
                ReaderGatingStyle gatingStyle = ReaderGatingStyle.getStyle(pikaConfig.getKey("Reader").optString("Gating Style"));

                logger.debug("Waiting for tag reads.... Gating: {} Style: {}", gating, gatingStyle);
                tags.add(tagQueue.take());

                
                tagQueue.drainTo(tags);

                logger.debug("Recieved {} raw tag reads to process", tags.size());

                tags.forEach(t -> {
                    if (!tagReadMap.containsKey(t.hexEPC)) {
                        tagReadMap.put(t.hexEPC, new ArrayList<>());
                    }
                    tagReadMap.get(t.hexEPC).add(t);
                });
                
                
                long gate = Instant.now().toEpochMilli() - gating;
                
                
                Map<String, TagRead> tagMap = new HashMap<>(1000);
                
                for (String epc : new ArrayList<>(tagReadMap.keySet())) {
                    // split the tags into a hash, saving the strongest read
                    if (tagReadMap.get(epc).getFirst().epochMilli < gate) {
                        tagReadMap.get(epc).forEach(t -> {
                            // Save the strongest read for each EPC value
                            String key = switch (gatingStyle) {
                                case ANTENNA ->
                                    t.hexEPC + t.getReaderID() + t.getReaderAntenna();
                                case BOX ->
                                    t.hexEPC;
                                case READER ->
                                    t.hexEPC + t.getReaderID();
                                default ->
                                    t.hexEPC;
                            };

                            if (tagMap.containsKey(key)) {
                                if (tagMap.get(key).rssi.compareTo(t.rssi) < 0) {
                                    tagMap.put(key, t);
                                }
                            } else {
                                tagMap.put(key, t);
                            }                    
                        });
                        tagReadMap.remove(epc);
                    }
                }

                if (!tagMap.isEmpty()) {
                    // For each read, post it to the handler
                    tagRouter.routeTagReads(tagMap.values()); 

                    // Update some stats
                    TagRead lastChipRead = tagMap.values().stream()
                            .sorted((t1, t2) -> t1.epochMilli.compareTo(t2.epochMilli))
                            .skip(tagMap.size() - 1).findFirst().get();

                    statusHandler.incrementReadCount(tagMap.size());
                    statusHandler.lastChipRead(lastChipRead);
                }
                
                Thread.sleep(1000); // take a nap for a second

            }
        } catch (InterruptedException ex) {
            logger.trace("Exiting " + tagProcessingThread.getName());
        }
    }

    public void processTagRead(TagRead tr) {
        logger.trace("Entering TagDAO::processTagRead");

        // Start the tag processing thread
        if (tagProcessingThread == null) {

            tagProcessingThread = new Thread(TagReadProcessor.getInstance());
            tagProcessingThread.setName("TagProcessingThread");
            tagProcessingThread.setDaemon(true);
            tagProcessingThread.setPriority(1);

            tagProcessingThread.start();

        }

        logger.debug("TagRead: {} Timestamp: {} Reader: {} Antenna: {} RSSI: {}", tr.getEPCDecimal(), tr.getTimestamp().format(formatter), tr.readerID, tr.antennaPortNumber, tr.rssi);
        tagQueue.add(tr);

        // Also ship all tag reads to the status handler for statistics
        statusHandler.postRead(tr);
        
        // Beep if we have not seen the tag before or have not seen it in the last 5 seconds. 
        // TODO:  Replace the AWT Toolkit beep with something better
        if (seenTags.containsKey(tr.hexEPC)) {
            if (Duration.between(seenTags.get(tr.hexEPC).timestamp, tr.timestamp).toSeconds() > 5) {
                seenTags.put(tr.hexEPC, tr);
                Toolkit.getDefaultToolkit().beep();
            }
        } else {
            seenTags.put(tr.hexEPC, tr);
            Toolkit.getDefaultToolkit().beep();
        }

        logger.trace("Exiting TagDAO::processTagRead");

    }

}
