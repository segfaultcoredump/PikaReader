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
package org.pikatimer.pikareader.readers.impinj;

import com.impinj.octane.ReaderModel;
import com.impinj.octane.SearchMode;
import org.pikatimer.pikareader.readers.profiles.ProfileManager;
import org.pikatimer.pikareader.readers.profiles.ReaderProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The inventory settings an Impinj reader uses for a profile.
 *
 * The defaults come from reader-profiles.json (readers.impinj_speedway or
 * readers.impinj_r700_series, picked by reader model, then the region table
 * for the reader's region family). The reader's "Profiles" entry in the
 * config overrides them. model_support adds per-model checks, such as the
 * R220's role warnings.
 *
 * @param rfModes RF mode candidates in order; the first one the reader
 * accepts is used. Empty means the reader's own default.
 * @param fallbackModes operator alternatives (set with a "Reader Mode"
 * override), for the /profile api and the log
 * @param sources where each setting came from: "profile", "override",
 * "assigned" (session) or "reader" (the reader-level config)
 * @param model the model key in model_support (e.g. "r220"), or null
 * @param warnings things the operator should know about this reader and
 * profile, e.g. an R220 on Start Mats
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
record ImpinjProfile(ReaderProfile profile, String readerKey, String model, String region, List<Integer> rfModes,
        List<Integer> fallbackModes, SearchMode searchMode, int session, int tagPopulation,
        ImpinjPowerLevels powerLevel, Map<String, String> sources, List<String> warnings) {

    private static final Logger logger = LoggerFactory.getLogger(ImpinjProfile.class);

    static final String SPEEDWAY = "impinj_speedway";
    static final String R700_SERIES = "impinj_r700_series";

    /**
     * The settings a reader's "Profiles" entry may override.
     */
    static final Set<String> OVERRIDE_KEYS = Set.of("Reader Mode", "Search Mode", "Session", "Tag Population", "Power Level");

    /**
     * R700 Series readers (R700, R720) have their own region-specific mode
     * tables. Every other Impinj reader uses the Speedway table, whose
     * candidate lists end with the standard LLRP mode 2, which every reader
     * has.
     */
    static String readerKey(ReaderModel model) {
        if (model == null) {
            return SPEEDWAY;
        }
        return switch (model) {
            case R700, R700v2, R720 ->
                R700_SERIES;
            default ->
                SPEEDWAY;
        };
    }

    /**
     * @return the model's key in the reader data's model_support, or null for
     * a model reader-profiles.json doesn't describe
     */
    static String modelKey(ReaderModel model) {
        if (model == null) {
            return null;
        }
        return switch (model) {
            case SpeedwayR220 ->
                "r220";
            case SpeedwayR420 ->
                "r420";
            case R700, R700v2 ->
                "r700";
            case R720 ->
                "r720";
            default ->
                null;
        };
    }

    /**
     * @param model the reader model, or null if unknown
     * @param detectedRegion the region family the reader reports, or null
     * @param regionDescription what the reader reported, for the log
     */
    static ImpinjProfile resolve(ProfileManager profiles, ReaderProfile profile, JSONObject readerConfig, Integer readerID,
            ReaderModel model, String detectedRegion, String regionDescription) {
        JSONObject overrides = profiles.overridesFor(readerConfig, profile);
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String key = readerKey(model);
        JSONObject data = profiles.getReaderData(key);
        String modelKey = modelKey(model);
        JSONObject support = modelKey == null ? null : data.optJSONObject("model_support", new JSONObject()).optJSONObject(modelKey);
        if (support == null) {
            logger.info("Reader {}: reader-profiles.json has no data for the {}. Using the {} table.", readerID, model == null ? "unknown model" : model, key);
            modelKey = null;
        }

        // The region table. The R420 table covers every region, so the
        // region only matters on readers with more than one table.
        JSONArray tables = data.getJSONArray("region_tables");
        String region;
        JSONObject table = null;
        if (tables.length() == 1) {
            table = tables.getJSONObject(0);
            region = profiles.getConfiguredRegion() != null ? profiles.getConfiguredRegion() : detectedRegion;
        } else {
            region = profiles.resolveRegion(detectedRegion, readerID, regionDescription);
            for (int i = 0; region != null && i < tables.length(); i++) {
                if (tables.getJSONObject(i).getJSONArray("applies_to").toList().contains(region)) {
                    table = tables.getJSONObject(i);
                    break;
                }
            }
        }

        // Some models are only sold for some regions (no R220 is ETSI HB or
        // Japan). A reader claiming another region is treated as a mismatch.
        JSONArray modelRegions = support == null ? null : support.optJSONArray("regions");
        if (table != null && region != null && modelRegions != null && !modelRegions.toList().contains(region)) {
            logger.error("Reader {}: the {} is only sold for regions {}, but this one is {}. Not applying the profile's reader mode.",
                    readerID, modelKey.toUpperCase(Locale.ROOT), modelRegions.toList(), region);
            warnings.add("Reader " + readerID + " (" + modelKey.toUpperCase(Locale.ROOT) + ") reports region " + region
                    + ", which this model is not sold for. It is using its own default reader mode.");
            table = null;
        }

        // Roles a model is too slow for (e.g. an R220's 200 tags/s on mats)
        if (support != null) {
            String name = modelKey.toUpperCase(Locale.ROOT);
            String rate = support.has("max_read_rate_tags_per_s") ? " (" + support.get("max_read_rate_tags_per_s") + " tags/s max)" : "";
            if (support.optJSONArray("not_recommended_roles", new JSONArray()).toList().contains(profile.id())) {
                String w = "Reader " + readerID + " is an " + name + rate + ", which is not recommended for " + profile.name()
                        + ". It may not read each tag often enough. It suits side panels and quiet splits.";
                logger.warn(w);
                warnings.add(w);
            } else if (support.optJSONArray("caution_roles", new JSONArray()).toList().contains(profile.id())) {
                String w = "Caution: reader " + readerID + " is an " + name + rate + ". Check that it reads each tag often enough for "
                        + profile.name() + ".";
                logger.warn(w);
                warnings.add(w);
            }
        }

        List<Integer> rfModes = new ArrayList<>();
        List<Integer> fallbackModes = new ArrayList<>();
        JSONObject role = table == null ? null : table.getJSONObject("roles").optJSONObject(profile.id());
        if (role != null) {
            role.getJSONArray("rf_mode").forEach(m -> rfModes.add(((Number) m).intValue()));
            role.optJSONArray("fallback_modes", new JSONArray()).forEach(m -> fallbackModes.add(((Number) m).intValue()));
            sources.put("Reader Mode", "profile");
        } else {
            logger.error("Reader {}: no {} reader mode for the {} profile in region {}. Leaving the reader mode to the reader.",
                    readerID, key, profile.id(), region == null ? "unknown" : region);
            sources.put("Reader Mode", "reader default");
        }

        String modeOverride = overrides.optString("Reader Mode", "");
        if (!modeOverride.isBlank()) {
            Integer mode = Impinj.parseReaderMode(modeOverride, readerID);
            if (mode != null) {
                rfModes.clear();
                rfModes.add(mode);
                sources.put("Reader Mode", "override");
            }
        }

        SearchMode searchMode;
        String searchOverride = overrides.optString("Search Mode", "");
        if (!searchOverride.isBlank()) {
            searchMode = Impinj.parseSearchMode(searchOverride, readerID);
            sources.put("Search Mode", "override");
        } else {
            searchMode = Impinj.parseSearchMode(data.optString("search_mode", ""), readerID);
            sources.put("Search Mode", "profile");
        }

        int session;
        if (overrides.has("Session")) {
            session = Impinj.clamp(readerID, "Session", overrides.optInt("Session", 2), 0, 3);
            sources.put("Session", "override");
        } else {
            session = profiles.sessionFor(readerID);
            sources.put("Session", "assigned");
        }

        int tagPopulation;
        if (overrides.has("Tag Population")) {
            tagPopulation = Impinj.clamp(readerID, "Tag Population", overrides.optInt("Tag Population", profile.tagPopulation()), 1, 65535);
            sources.put("Tag Population", "override");
        } else {
            tagPopulation = profile.tagPopulation();
            sources.put("Tag Population", "profile");
        }

        ImpinjPowerLevels powerLevel;
        if (overrides.has("Power Level")) {
            powerLevel = ImpinjPowerLevels.getLevel(overrides.optString("Power Level"));
            sources.put("Power Level", "override");
        } else {
            powerLevel = ImpinjPowerLevels.getLevel(readerConfig.optString("Power Level"));
            sources.put("Power Level", "reader");
        }

        return new ImpinjProfile(profile, key, modelKey, region, List.copyOf(rfModes), List.copyOf(fallbackModes), searchMode,
                session, tagPopulation, powerLevel, sources, List.copyOf(warnings));
    }

    ImpinjProfile withFallbackModes(List<Integer> modes) {
        return new ImpinjProfile(profile, readerKey, model, region, rfModes, List.copyOf(modes), searchMode, session, tagPopulation,
                powerLevel, sources, warnings);
    }

    /**
     * @param appliedMode the RF mode the reader accepted (null = its default)
     */
    JSONObject toJSON(Integer appliedMode) {
        JSONObject o = new JSONObject();
        o.put("profile", profile.id());
        o.put("table", readerKey);
        o.put("model", model == null ? JSONObject.NULL : model);
        o.put("region", region == null ? JSONObject.NULL : region);
        o.put("readerMode", ImpinjRfMode.describe(appliedMode));
        o.put("readerModeCandidates", rfModes.stream().map(ImpinjRfMode::describe).toList());
        o.put("fallbackModes", fallbackModes.stream().map(ImpinjRfMode::describe).toList());
        o.put("searchMode", searchMode.name());
        o.put("session", session);
        o.put("tagPopulation", tagPopulation);
        o.put("powerLevel", powerLevel.getLevel());
        o.put("sources", new JSONObject(sources));
        o.put("warnings", warnings);
        return o;
    }
}
