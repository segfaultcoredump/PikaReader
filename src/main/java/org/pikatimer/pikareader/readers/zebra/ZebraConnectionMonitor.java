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
package org.pikatimer.pikareader.readers.zebra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the connection lifecycle for one Zebra reader: the initial connect,
 * spotting a dead link (the LLRP toolkit has no "connection closed" callback,
 * so we watch for keepalives), reconnecting, and putting the reader back into
 * read mode if it was reading when the link dropped.
 *
 * Runs on its own virtual thread, one per reader.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ZebraConnectionMonitor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ZebraConnectionMonitor.class);

    private static final long CHECK_INTERVAL_MS = 1000;
    private static final long RECONNECT_DELAY_MS = 5000;

    private final Zebra reader;
    private volatile boolean running = true;

    public ZebraConnectionMonitor(Zebra reader) {
        this.reader = reader;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        long nextConnectAttempt = 0;

        while (running) {
            try {
                if (reader.isConnected()) {
                    reader.checkLink();
                    if (!reader.isConnected()) {
                        // The link just dropped. Give the reader a moment before retrying.
                        nextConnectAttempt = System.currentTimeMillis() + CHECK_INTERVAL_MS;
                    }
                } else if (System.currentTimeMillis() >= nextConnectAttempt) {
                    if (reader.connect()) {
                        if (reader.wantsToRead()) {
                            logger.info("Putting reader {} ({}) back into read mode...", reader.getID(), reader.getIP());
                            reader.resumeReading();
                        }
                    } else {
                        nextConnectAttempt = System.currentTimeMillis() + RECONNECT_DELAY_MS;
                        logger.info("Reader {} ({}) is not connected. Retrying in {} seconds.", reader.getID(), reader.getIP(), RECONNECT_DELAY_MS / 1000);
                    }
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException ex) {
                logger.debug("Connection monitor for reader {} interrupted", reader.getID());
                return;
            } catch (Exception ex) {
                logger.error("Connection monitor error for reader {}", reader.getID(), ex);
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }
}
