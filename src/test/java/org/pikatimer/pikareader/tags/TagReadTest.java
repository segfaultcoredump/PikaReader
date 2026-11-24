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

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chip number (worked out lazily) and the streamed JSON used by /rewind.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
class TagReadTest {

    private static final String EPC = "E28011700000020A1B2C3D01";

    private static TagRead read(String epc, int antenna, Double rssi) {
        TagRead t = new TagRead();
        t.setEPC(epc);
        t.setReaderID(1);
        t.setReaderAntenna(antenna);
        t.setPeakRSSI(rssi);
        t.setTimestamp(LocalDateTime.of(2026, 11, 12, 8, 30, 15, 123_000_000));
        t.setEpochMilli(1_794_486_615_123L);
        t.setTZOffset("-06:00");
        return t;
    }

    @Test
    void chipDefaultsToTheEpcInDecimal() {
        TagRead t = read(EPC, 1, -55.0);
        assertEquals(new BigInteger(EPC, 16).toString(), t.getChip());
        assertEquals(t.getEPCDecimal(), t.getChip());
        assertEquals(t.getEPCDecimal(), t.toJSONObject().getString("chip"));
    }

    @Test
    void hexChipOverridesTheDecimalDefault() {
        TagRead t = read("0000000000000000000004D2", 1, -55.0);
        t.setChip(t.getEPCHex());
        assertEquals("4D2", t.getChip());
        assertEquals("4D2", t.toJSONObject().getString("chip"));
    }

    @Test
    void settingTheEpcAgainResetsTheChip() {
        TagRead t = read(EPC, 1, -55.0);
        assertEquals(t.getEPCDecimal(), t.getChip());
        t.setEPC("0");
        assertEquals("0", t.getChip());
    }

    @Test
    void readsLoadedFromJsonKeepTheirChip() {
        TagRead t = read(EPC, 2, -61.5);
        t.setChip("1234");
        TagRead loaded = new TagRead(new JSONObject(t.toJSON()));
        assertEquals("1234", loaded.getChip());
        assertEquals(t.toJSON(), loaded.toJSON());
    }

    @Test
    void streamedArrayMatchesJsonArray() throws IOException {
        List<TagRead> reads = new ArrayList<>();
        reads.add(read(EPC, 1, -55.0));
        reads.add(read("0000000000000000000004D2", 2, null));
        TagRead hex = read("00000000000000000000ABCD", 3, -70.25);
        hex.setChip(hex.getEPCHex());
        reads.add(hex);

        JSONArray expected = new JSONArray();
        reads.forEach(r -> expected.put(r.toJSONObject()));

        StringWriter out = new StringWriter();
        TagRead.writeJSONArray(reads, out);
        assertEquals(expected.toString(), out.toString());
        assertEquals(3, new JSONArray(out.toString()).length());
    }

    @Test
    void streamedEmptyArray() throws IOException {
        StringWriter out = new StringWriter();
        TagRead.writeJSONArray(List.of(), out);
        assertEquals("[]", out.toString());
        assertEquals(new JSONArray().toString(), out.toString());
    }

    @Test
    void sortsByWhenTheReadWasTakenNotByItsLabel() {
        // 2026-11-01 01:30 in America/Chicago happens twice: CDT, then CST
        TagRead firstPass = read(EPC, 1, -55.0);
        firstPass.setTimestamp(LocalDateTime.of(2026, 11, 1, 1, 30));
        firstPass.setEpochMilli(java.time.Instant.parse("2026-11-01T06:30:00Z").toEpochMilli());
        TagRead between = read(EPC, 1, -55.0);
        between.setTimestamp(LocalDateTime.of(2026, 11, 1, 1, 45));
        between.setEpochMilli(java.time.Instant.parse("2026-11-01T06:45:00Z").toEpochMilli());
        TagRead secondPass = read(EPC, 1, -55.0);
        secondPass.setTimestamp(LocalDateTime.of(2026, 11, 1, 1, 10));
        secondPass.setEpochMilli(java.time.Instant.parse("2026-11-01T07:10:00Z").toEpochMilli());

        List<TagRead> reads = new ArrayList<>(List.of(secondPass, between, firstPass));
        reads.sort(null);
        assertEquals(List.of(firstPass, between, secondPass), reads);
    }
}
