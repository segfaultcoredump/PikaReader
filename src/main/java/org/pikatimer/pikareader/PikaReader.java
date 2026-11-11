/*
 * Copyright (C) 2024 John Garner <segfaultcoredump@gmail.com>
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
package org.pikatimer.pikareader;

import org.pikatimer.pikareader.conf.PikaConfig;
import org.pikatimer.pikareader.http.HTTPHandler;
import org.pikatimer.pikareader.readers.ReaderHandler;
import org.pikatimer.pikareader.status.StatusHandler;
import org.pikatimer.pikareader.tags.TagDB;
import org.pikatimer.pikareader.util.DiscoveryListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class PikaReader {

    private static final Logger logger = LoggerFactory.getLogger(PikaReader.class);

    public static void main(String[] args) {

        PikaReader pr = new PikaReader();
        pr.start();

    }

    /**
     * The version and build time, from pikareader-build.properties (filled in
     * by Maven at build time).
     */
    static String buildInfo() {
        Properties p = new Properties();
        try (InputStream in = PikaReader.class.getResourceAsStream("/pikareader-build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ex) {
            logger.debug("Unable to read pikareader-build.properties", ex);
        }
        return p.getProperty("version", "unknown") + " (built " + p.getProperty("built", "unknown") + ")";
    }

    public void start() {

        System.out.println("""

            8888888b.  d8b 888               8888888b.                         888                  
            888   Y88b Y8P 888               888   Y88b                        888                  
            888    888     888               888    888                        888                  
            888   d88P 888 888  888  8888b.  888   d88P  .d88b.   8888b.   .d88888  .d88b.  888d888 
            8888888P"  888 888 .88P     "88b 8888888P"  d8P  Y8b     "88b d88" 888 d8P  Y8b 888P"   
            888        888 888888K  .d888888 888 T88b   88888888 .d888888 888  888 88888888 888     
            888        888 888 "88b 888  888 888  T88b  Y8b.     888  888 Y88b 888 Y8b.     888     
            888        888 888  888 "Y888888 888   T88b  "Y8888  "Y888888  "Y88888  "Y8888  888     

                                            ©2026 by John Garner
                                           https://PikaTimer.com/
                                      Released under the GPL-3.0 license.
                                                                                       
                                                                                                                   """);
        // Log which build is running, so a stale install is easy to spot
        logger.info("PikaReader {}", buildInfo());

        // Read Config
        PikaConfig pikaConfig = PikaConfig.getInstance();

        // Startup DB;
        TagDB tagDB = TagDB.getInstance();

        // Setup Readers
        ReaderHandler readerHandler = ReaderHandler.getInstance();

        // Hard code this until the Web UI and buttons are setup
        //readerHandler.setClocks();
        //readerHandler.startReading();

        // Start http listener
        HTTPHandler httpHandler = HTTPHandler.getInstance();

 
        
        // Start broadcast listener so others can find us
        DiscoveryListener.startDiscoveryListener();
        
        // start the status handler
        StatusHandler statusHandler = StatusHandler.getInstance();      
       
        // The JVM runs shutdown hooks on SIGINT (Ctrl-C), SIGTERM and SIGHUP
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received. Stopping Readers...");
            readerHandler.stopReading();

            // Write out any reads still waiting for the DB thread's next cycle
            tagDB.flush();

            logger.info("Stopping Jetty");
            httpHandler.stopHTTPD();

            logger.info("PikaReader Stopped...");
        }, "PikaReaderShutdown"));

        System.out.println("Press Ctrl-C (or send SIGTERM/SIGHUP) to exit.");

        // Block until a signal triggers the shutdown hook and the JVM exits
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
