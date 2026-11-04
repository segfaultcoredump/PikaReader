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

import com.pikatimer.pikareader.conf.PikaConfig;
import com.pikatimer.pikareader.readers.ReaderClock;
import com.pikatimer.pikareader.tags.TagRead;
import com.pikatimer.pikareader.tags.TagReadProcessor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.llrp.ltk.generated.interfaces.EPCParameter;
import org.llrp.ltk.generated.messages.RO_ACCESS_REPORT;
import org.llrp.ltk.generated.parameters.EPCData;
import org.llrp.ltk.generated.parameters.EPC_96;
import org.llrp.ltk.generated.parameters.TagReportData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns LLRP RO_ACCESS_REPORTs into TagReads and hands them to the
 * TagReadProcessor. Runs on the reader's LLRP IO thread, so it must stay quick
 * and must never block on the reader.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ZebraTagReportHandler {

    private static final Logger logger = LoggerFactory.getLogger(ZebraTagReportHandler.class);

    private final TagReadProcessor tagReadProcessor = TagReadProcessor.getInstance();
    private final Zebra reader;
    private final ReaderClock clock;

    public ZebraTagReportHandler(Zebra reader, ReaderClock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    public void process(RO_ACCESS_REPORT report, long piReceiveMicros) {
        List<TagReportData> tags = report.getTagReportDataList();
        if (tags == null || tags.isEmpty()) {
            return;
        }

        ZoneId zoneId = PikaConfig.getInstance().getTimezoneId();
        Integer readerID = reader.getID();

        for (TagReportData t : tags) {
            try {
                String epc = epcHex(t.getEPCParameter());
                if (epc == null) {
                    logger.debug("Reader {} sent a tag report without a usable EPC", readerID);
                    continue;
                }

                Integer antenna = t.getAntennaID() == null ? 0 : t.getAntennaID().getAntennaID().intValue();
                reader.noteAntennaSeen(antenna);

                Double rssi = t.getPeakRSSI() == null ? -100.0 : (double) t.getPeakRSSI().getPeakRSSI().intValue();

                // Pull the reader's timestamps. With N=1 reporting First and Last
                // are normally the same read.
                ReaderClock.Kind firstKind = null;
                Long first = null;
                if (t.getFirstSeenTimestampUTC() != null) {
                    firstKind = ReaderClock.Kind.UTC;
                    first = t.getFirstSeenTimestampUTC().getMicroseconds().toLong();
                } else if (t.getFirstSeenTimestampUptime() != null) {
                    firstKind = ReaderClock.Kind.UPTIME;
                    first = t.getFirstSeenTimestampUptime().getMicroseconds().toLong();
                }

                ReaderClock.Kind lastKind = null;
                Long last = null;
                if (t.getLastSeenTimestampUTC() != null) {
                    lastKind = ReaderClock.Kind.UTC;
                    last = t.getLastSeenTimestampUTC().getMicroseconds().toLong();
                } else if (t.getLastSeenTimestampUptime() != null) {
                    lastKind = ReaderClock.Kind.UPTIME;
                    last = t.getLastSeenTimestampUptime().getMicroseconds().toLong();
                }

                // The last-seen time is closest to when the report was sent,
                // so it gives the tightest clock offset sample.
                if (last != null) {
                    clock.sample(lastKind, last, piReceiveMicros);
                } else if (first != null) {
                    clock.sample(firstKind, first, piReceiveMicros);
                }

                long epochMicros;
                if (first != null) {
                    epochMicros = clock.toEpochMicros(firstKind, first, piReceiveMicros);
                } else if (last != null) {
                    epochMicros = clock.toEpochMicros(lastKind, last, piReceiveMicros);
                } else {
                    epochMicros = piReceiveMicros;
                }

                Instant timestamp = Instant.EPOCH.plus(epochMicros, ChronoUnit.MICROS);

                logger.trace("Tag Read: {} at {} on reader {} port {} rssi {}", epc, timestamp, readerID, antenna, rssi);

                TagRead tr = new TagRead();
                tr.setReaderID(readerID);
                tr.setEPC(epc);
                tr.setReaderAntenna(antenna);
                tr.setPeakRSSI(rssi);
                tr.setEpochMilli(timestamp.toEpochMilli());
                tr.setTZOffset(ZonedDateTime.ofInstant(timestamp, zoneId).getOffset().toString());
                tr.setTimestamp(LocalDateTime.ofInstant(timestamp, zoneId));

                tagReadProcessor.processTagRead(tr);
            } catch (Exception ex) {
                logger.error("Reader {}: unable to process tag report", readerID, ex);
            }
        }
    }

    /**
     * Normalize the EPC into an upper case hex string with no separators.
     */
    static String epcHex(EPCParameter p) {
        String raw = null;
        if (p instanceof EPC_96 e96 && e96.getEPC() != null) {
            raw = e96.getEPC().toString();
        } else if (p instanceof EPCData data && data.getEPC() != null) {
            raw = data.getEPC().toString();
        }
        if (raw == null) {
            return null;
        }
        String hex = raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        return hex.isEmpty() ? null : hex;
    }
}
