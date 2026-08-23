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

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

/**
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class TagRead implements Comparable<TagRead> {

    protected String hexEPC;
    protected String chip;
    //protected String readerIP;
    protected LocalDateTime timestamp;
    protected Double rssi;
    protected Integer antennaPortNumber;
    protected Integer readerID;
    protected Long epochMilli;
    protected String tzOffset;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd' 'HH:mm:ss.SSS");

    public void setEPC(String epc) {
        hexEPC = epc;
        chip = getEPCDecimal(); // default
    }

    public String getEPC() {
        return hexEPC;
    }
    
    public String getChip(){
        return chip;
    }
    
    public void setChip(String c){
        chip = c;
    }

    public String getEPCDecimal() {
        return new BigInteger(hexEPC, 16).toString();
    }
    
    public String getEPCHex(){
        // strip leading 0 but return 0 if all 0's
        int i = 0;
        while (i < hexEPC.length() - 1 && hexEPC.charAt(i) == '0') {
            i++;
        }
        return hexEPC.substring(i);
    }

    public void setTimestamp(LocalDateTime tagTimestamp) {
        timestamp = tagTimestamp;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setPeakRSSI(Double peakRssiInDbm) {
        rssi = peakRssiInDbm;
    }

    public Double getPeakRSSI() {
        return rssi;
    }

    public void setReaderAntenna(Integer antennaPortNumber) {
        this.antennaPortNumber = antennaPortNumber;
    }

    public Integer getReaderAntenna() {
        return antennaPortNumber;
    }

    public Integer getReaderID() {
        return readerID;
    }

    public void setReaderID(Integer readerID) {
        this.readerID = readerID;
    }

    public String toJSON() {
        return toJSONObject().toString();
    }

    public JSONObject toJSONObject() {
        JSONObject msg = new JSONObject();
        msg.put("chip", chip);
        msg.put("epc", getEPCHex());
        msg.put("timestamp", getTimestamp().format(formatter));
        msg.put("reader", readerID);
        msg.put("antenna", antennaPortNumber);
        msg.put("rssi", rssi);
        msg.put("tz", tzOffset);
        msg.put("epochMilli",epochMilli);
        return msg;
    }

    public TagRead() {
        // do nothing here
    }

    public TagRead(JSONObject o) {
        readerID = o.optInt("reader");
        antennaPortNumber = o.getInt("antenna");
        rssi = o.optDouble("rssi", 0.0);
        timestamp = LocalDateTime.parse(o.getString("timestamp"), formatter);
        //hexEPC = new BigInteger(o.getString("epc"), 10).toString(16); 
        hexEPC = o.optString("epc",o.getString("chip"));
        chip = o.getString("chip");
        tzOffset = o.optString("tz", "Z");
        epochMilli = o.optLong("epochMilli");
    }

    @Override
    public int compareTo(TagRead other) {
        return this.timestamp.compareTo(other.timestamp);
    }

    public void setEpochMilli(Long epochMilli) {
        this.epochMilli = epochMilli;
    }
    
    public Long getEpochMilli(){
        return this.epochMilli;
    }

    public void setTZOffset(String offset) {
        this.tzOffset = offset;
    }
}
