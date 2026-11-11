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
package org.pikatimer.pikareader.readers.impinj;

import com.impinj.octane.ConnectionLostListener;
import com.impinj.octane.ImpinjReader;
import org.pikatimer.pikareader.readers.ReaderHandler;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ImpinjConnectionLostListener implements ConnectionLostListener {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ImpinjConnectionLostListener.class);
    private final Impinj reader;

    public ImpinjConnectionLostListener(Impinj reader) {
        this.reader = reader;
    }

    // TODO:  Update parent Impinj impinjReader connected status.
    // Let it handle the reconnect and then resume reading if it was in reading mode
    @Override
    public void onConnectionLost(ImpinjReader impinjReader) {
        logger.error("Connection to reader {} lost!", impinjReader.getAddress());
        impinjReader.disconnect();

        logger.info("Attempting to reconnect to {}...", impinjReader.getAddress());

        Integer retryLimit = 50;

        // Reconnect through Impinj.connect(), which uses the configured port
        // and TLS setting and re-applies all of the reader settings. (The
        // SDK's own no-argument connect() always uses plain LLRP on 5084.)
        while (retryLimit-- > 0) {
            if (reader.connect()) {
                logger.info("Reconnected to {}",reader.getIP());
                logger.info("Reconnected reader status: Reading {}", reader.isReading());
                if (ReaderHandler.getInstance().isReading()) {
                    logger.info("Putting reader {} into read mode...",reader.getIP());
                    reader.stopReading();
                    reader.startReading();
                    logger.info("Done restarting {}",reader.getIP());
                }
                return;
            }
            logger.info("Unable to reconnect to {}. Sleeping 10 seconds and will try again...", reader.getIP());
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ex1) {
                logger.error("Retry Thread Interrupted!", ex1);
                return;
            }
        }
        logger.error("Giving up on reconnecting to {} for now", reader.getIP());
    }

}
