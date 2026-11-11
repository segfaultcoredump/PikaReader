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
package org.pikatimer.pikareader.readers.profiles;

import java.util.Locale;
import org.json.JSONObject;

/**
 * A reader profile (a "role" in reader-profiles.json), such as Finish Mats.
 *
 * @param id the key in reader-profiles.json, e.g. "finish_mats"
 * @param name the display name, e.g. "Finish Mats"
 * @param description what the profile is for
 * @param tagPopulation the default tag population estimate
 * @param order sort order for lists and the web UI
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public record ReaderProfile(String id, String name, String description, int tagPopulation, int order) {

    /**
     * Matches the id or the display name, ignoring case, spaces, dashes and
     * underscores, so "Finish Panels", "finish_side_panels" and
     * "FINISH-SIDE-PANELS" all match the Finish Panels profile.
     */
    public boolean matches(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String n = normalize(s);
        return n.equals(normalize(id)) || n.equals(normalize(name));
    }

    public JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("description", description);
        o.put("tagPopulation", tagPopulation);
        return o;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }

    static String normalize(String s) {
        return s.trim().replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
