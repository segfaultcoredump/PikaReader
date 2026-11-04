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
package com.pikatimer.pikareader.readers.zebra;

import org.llrp.ltk.generated.enumerations.AntennaEventType;
import org.llrp.ltk.generated.enumerations.ROSpecEventType;
import org.llrp.ltk.generated.enumerations.StatusCode;
import org.llrp.ltk.generated.interfaces.Timestamp;
import org.llrp.ltk.generated.messages.ERROR_MESSAGE;
import org.llrp.ltk.generated.messages.KEEPALIVE;
import org.llrp.ltk.generated.messages.READER_EVENT_NOTIFICATION;
import org.llrp.ltk.generated.messages.RO_ACCESS_REPORT;
import org.llrp.ltk.generated.messages.START_ROSPEC_RESPONSE;
import org.llrp.ltk.generated.parameters.AntennaEvent;
import org.llrp.ltk.generated.parameters.ROSpecEvent;
import org.llrp.ltk.generated.parameters.ReaderEventNotificationData;
import org.llrp.ltk.generated.parameters.UTCTimestamp;
import org.llrp.ltk.generated.parameters.Uptime;
import org.llrp.ltk.net.LLRPEndpoint;
import org.llrp.ltk.types.LLRPMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives every asynchronous LLRP message from one Zebra reader: tag reports,
 * reader events, keepalives and the responses to any fire-and-forget commands.
 *
 * This is called on the reader's LLRP (MINA) IO thread. It must never call
 * LLRPConnection.transact() or anything else that waits on the reader, because
 * the response could only be delivered by the thread that is now blocked.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ZebraMessageHandler implements LLRPEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(ZebraMessageHandler.class);

    private final Zebra reader;
    private final ZebraTagReportHandler tagReportHandler;
    private final ZebraClock clock;

    private volatile long lastMessageNanos = System.nanoTime();

    public ZebraMessageHandler(Zebra reader, ZebraTagReportHandler tagReportHandler, ZebraClock clock) {
        this.reader = reader;
        this.tagReportHandler = tagReportHandler;
        this.clock = clock;
    }

    /**
     * Mark the link as alive right now.
     */
    public void touch() {
        lastMessageNanos = System.nanoTime();
    }

    public long millisSinceLastMessage() {
        return (System.nanoTime() - lastMessageNanos) / 1_000_000L;
    }

    @Override
    public void messageReceived(LLRPMessage message) {
        long piReceiveMicros = ZebraClock.nowMicros();
        touch();

        try {
            switch (message) {
                case RO_ACCESS_REPORT report ->
                    tagReportHandler.process(report, piReceiveMicros);
                case READER_EVENT_NOTIFICATION notification ->
                    handleEvent(notification.getReaderEventNotificationData(), piReceiveMicros);
                case KEEPALIVE k ->
                    logger.trace("Got a keepalive from reader {}", reader.getID());
                case START_ROSPEC_RESPONSE response -> {
                    // Responses to the non-blocking restarts sent by the DualTarget loop
                    if (response.getLLRPStatus() != null && response.getLLRPStatus().getStatusCode().intValue() != StatusCode.M_Success) {
                        logger.error("Reader {} refused to restart its ROSpec: {}", reader.getID(), Zebra.describe(response.getLLRPStatus()));
                        reader.onROSpecRestartFailed(this);
                    }
                }
                case ERROR_MESSAGE error ->
                    logger.error("Reader {} sent an LLRP error: {}", reader.getID(), Zebra.describe(error.getLLRPStatus()));
                default ->
                    logger.debug("Reader {} sent unhandled message {}", reader.getID(), message.getName());
            }
        } catch (Exception ex) {
            // Never let an exception escape into the LLRP IO thread
            logger.error("Reader {}: error handling {}", reader.getID(), message.getName(), ex);
        }
    }

    @Override
    public void errorOccured(String error) {
        reader.onLinkError(this, error);
    }

    private void handleEvent(ReaderEventNotificationData data, long piReceiveMicros) {
        if (data == null) {
            return;
        }

        // Every reader event carries the reader's clock. Use it as a sample.
        Timestamp ts = data.getTimestamp();
        if (ts instanceof UTCTimestamp utc && utc.getMicroseconds() != null) {
            clock.sample(ZebraClock.Kind.UTC, utc.getMicroseconds().toLong(), piReceiveMicros);
        } else if (ts instanceof Uptime up && up.getMicroseconds() != null) {
            clock.sample(ZebraClock.Kind.UPTIME, up.getMicroseconds().toLong(), piReceiveMicros);
        }

        if (data.getConnectionAttemptEvent() != null) {
            logger.info("Reader {} connection attempt status: {}", reader.getID(), data.getConnectionAttemptEvent().getStatus());
        }

        ROSpecEvent rospecEvent = data.getROSpecEvent();
        if (rospecEvent != null) {
            switch (rospecEvent.getEventType().intValue()) {
                case ROSpecEventType.Start_Of_ROSpec ->
                    reader.onROSpecStarted(this);
                case ROSpecEventType.End_Of_ROSpec ->
                    reader.onROSpecEnded(this, false);
                case ROSpecEventType.Preemption_Of_ROSpec ->
                    reader.onROSpecEnded(this, true);
                default ->
                    logger.debug("Reader {} ROSpec event {}", reader.getID(), rospecEvent.getEventType());
            }
        }

        if (data.getAISpecEvent() != null) {
            logger.trace("Reader {} AISpec event {}", reader.getID(), data.getAISpecEvent().getEventType());
        }

        AntennaEvent antennaEvent = data.getAntennaEvent();
        if (antennaEvent != null) {
            boolean connected = antennaEvent.getEventType().intValue() == AntennaEventType.Antenna_Connected;
            reader.updateAntennaStatus(antennaEvent.getAntennaID().intValue(), connected);
        }

        if (data.getReaderExceptionEvent() != null) {
            logger.warn("Reader {} exception event: {}", reader.getID(), data.getReaderExceptionEvent().getMessage());
        }

        if (data.getReportBufferLevelWarningEvent() != null) {
            logger.warn("Reader {} report buffer is {}% full", reader.getID(),
                    data.getReportBufferLevelWarningEvent().getReportBufferPercentageFull());
        }

        if (data.getReportBufferOverflowErrorEvent() != null) {
            logger.error("Reader {} report buffer overflowed. Tag reads have been lost!", reader.getID());
        }

        if (data.getConnectionCloseEvent() != null) {
            logger.warn("Reader {} is closing the LLRP connection", reader.getID());
            reader.onLinkError(this, "reader closed the connection");
        }
    }
}
