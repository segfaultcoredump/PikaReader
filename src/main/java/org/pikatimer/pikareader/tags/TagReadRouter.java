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
import org.pikatimer.pikareader.http.HTTPHandler;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class TagReadRouter implements Runnable {

    // Unbounded so routeTagReads() never blocks or throws on the caller's thread
    private static final BlockingQueue<Collection<TagRead>> tagQueue = new LinkedBlockingQueue<>();
    private static final Logger logger = LoggerFactory.getLogger(TagReadRouter.class);
    private static final PikaConfig pikaConfig = PikaConfig.getInstance();
    private static final HTTPHandler httpHandler = HTTPHandler.getInstance();
    private static final TagDB tagDB = TagDB.getInstance();

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd' 'HH:mm:ss.SSS");

    private final Thread tagRoutingThread;

    /**
     * SingletonHolder is loaded on the first execution of
     * Singleton.getInstance() or the first access to SingletonHolder.INSTANCE,
     * not before.
     */
    private static class SingletonHolder {

        private static final TagReadRouter INSTANCE = new TagReadRouter();

    }

    public static TagReadRouter getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private TagReadRouter() {
        tagRoutingThread = new Thread(this);
        tagRoutingThread.setName("TagRouterThread");
        tagRoutingThread.setDaemon(true);
        tagRoutingThread.setPriority(1);
        tagRoutingThread.start();
    }

    @Override
    public void run() {
        Boolean hexChip = false; 
        
        if (pikaConfig.getStringValue("ChipFormat").toUpperCase().startsWith("H")) hexChip = true;
        
        try {
            while (true) {
                Collection<TagRead> tr = tagQueue.take();

                // A bad batch must never kill this thread, or every read
                // after it would be lost
                try {
                    //If they want the chip in hex, set it now
                    if (hexChip) {
                        tr.forEach(t -> {
                            t.setChip(t.getEPCHex());
                        });
                    }

                    // send a copy to the DB
                    try {
                        tagDB.addReads(tr);
                    } catch (RuntimeException ex) {
                        logger.error("Error sending {} tag reads to the DB", tr.size(), ex);
                    }

                    // send a copy to the websocket handler
                    try {
                        httpHandler.sendTags(tr);
                    } catch (RuntimeException ex) {
                        logger.error("Error sending {} tag reads to the websocket clients", tr.size(), ex);
                    }

                    // if we are uploading to PikaTagRelay, send a copy there too
                } catch (RuntimeException ex) {
                    logger.error("Error routing {} tag reads", tr.size(), ex);
                }
            }
        } catch (InterruptedException ex) {
            logger.trace("Exiting " + tagRoutingThread.getName());
        }
    }

    public void routeTagReads(Collection<TagRead> tr) {
        logger.trace("Entering TagReadRouter::processTagReads");

        logger.debug("TagRouter::processTagReads: Recieved {} new tags to process.", tr.size());
        // Queue a copy so the caller can't change it once it's handed off
        tagQueue.add(new ArrayList<>(tr));

        logger.trace("Exiting TagReadRouter::processTagReads");

    }

}
