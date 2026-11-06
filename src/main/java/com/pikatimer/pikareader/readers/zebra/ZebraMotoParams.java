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

import java.io.ByteArrayOutputStream;
import org.json.JSONObject;
import org.llrp.ltk.generated.parameters.Custom;
import org.llrp.ltk.types.BytesToEnd_HEX;
import org.llrp.ltk.types.LLRPBitList;
import org.llrp.ltk.types.UnsignedInteger;

/**
 * Zebra (Motorola, LLRP vendor 161) custom parameters for the
 * C1G2InventoryCommand. The Octane SDK's LTK has no Zebra extensions, so they
 * are encoded by hand from the Zebra RFID Reader Software Interface Control
 * Guide ("LLRP Custom Extensions"):
 *
 * <pre>
 * MotoAntennaConfig        (subtype 703): [MotoAntennaStopCondition] [MotoAntennaPhysicalPortConfig] [MotoAntennaQueryConfig]
 * MotoAntennaStopCondition (subtype 704): u8 AntennaStopTrigger (0 = Dwell_Time, 1 = Number_Inventory_Cycles),
 *                                         u16 AntennaStopConditionValue (ms or inventory rounds)
 * MotoAntennaQueryConfig   (subtype 710): 16 bits: S (EnableSLAll, bit 0), B (EnableABFlip, bit 1), 14 reserved
 * </pre>
 *
 * Each is a standard LLRP Custom parameter (type 1023, length, vendor ID,
 * subtype) and the sub-parameters are nested in MotoAntennaConfig's data.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
final class ZebraMotoParams {

    private ZebraMotoParams() {
    }

    /**
     * MotoAntennaConfig with the antenna stop condition and/or the query
     * config (SL All, A/B flip). The ids and flags come from
     * reader-profiles.json (zebra_llrp_custom).
     *
     * @param inventoryCycles inventory rounds per antenna before the reader
     * moves to the next antenna
     * @param stopCondition include MotoAntennaStopCondition
     * @param queryConfig include MotoAntennaQueryConfig
     * @return the parameter, or null when neither is included
     */
    static Custom antennaConfig(JSONObject zebraCustom, int inventoryCycles, boolean stopCondition, boolean queryConfig) {
        if (!stopCondition && !queryConfig) {
            return null;
        }
        long vendor = zebraCustom.getLong("vendor_id");
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        if (stopCondition) {
            int trigger = zebraCustom.getInt("antenna_stop_trigger_number_inventory_cycles");
            int cycles = Math.max(1, Math.min(0xFFFF, inventoryCycles));
            data.writeBytes(encode(custom(vendor, zebraCustom.getLong("moto_antenna_stop_condition_subtype"),
                    new byte[]{(byte) trigger, (byte) (cycles >> 8), (byte) cycles})));
        }

        if (queryConfig) {
            int flags = 0;
            if (zebraCustom.optBoolean("enable_sl_all", true)) {
                flags |= 0x8000;
            }
            if (zebraCustom.optBoolean("enable_ab_flip", true)) {
                flags |= 0x4000;
            }
            data.writeBytes(encode(custom(vendor, zebraCustom.getLong("moto_antenna_query_config_subtype"),
                    new byte[]{(byte) (flags >> 8), (byte) flags})));
        }

        return custom(vendor, zebraCustom.getLong("moto_antenna_config_subtype"), data.toByteArray());
    }

    static Custom custom(long vendor, long subtype, byte[] data) {
        Custom c = new Custom();
        c.setVendorIdentifier(new UnsignedInteger(vendor));
        c.setParameterSubtype(new UnsignedInteger(subtype));
        c.setData(new BytesToEnd_HEX(new LLRPBitList(data)));
        return c;
    }

    /**
     * @return the parameter as it goes on the wire, header included
     */
    static byte[] encode(Custom c) {
        return c.encodeBinary().toByteArray();
    }
}
