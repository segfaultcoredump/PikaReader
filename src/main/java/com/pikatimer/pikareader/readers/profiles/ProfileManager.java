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
package com.pikatimer.pikareader.readers.profiles;

import com.pikatimer.pikareader.conf.PikaConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;
import org.llrp.ltk.generated.enumerations.CommunicationsStandard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reader profiles (Start Mats, Finish Panels, ...).
 *
 * The profile data lives in the reader-profiles.json resource. One profile is
 * selected at a time for every reader. It is stored in the config as
 * "Reader" → "Profile", and each reader entry can override a profile's
 * settings in its "Profiles" object.
 *
 * This class only reads the config and the resource, so the reader drivers
 * can use it from their own connect threads while the ReaderHandler is still
 * being built.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class ProfileManager {

    private static final Logger logger = LoggerFactory.getLogger(ProfileManager.class);

    private static final String RESOURCE = "/reader-profiles.json";
    private static final String FALLBACK_PROFILE = "finish_mats";

    /**
     * The region families used by reader-profiles.json, as set with
     * "Reader" → "Region".
     */
    public static final List<String> REGION_FAMILIES = List.of("fcc", "global_other", "etsi_lb", "etsi_hb", "japan");

    /**
     * Reader-level keys that profiles now own. They are ignored (with a
     * warning) and belong in the reader's "Profiles" overrides instead.
     */
    private static final List<String> PROFILE_KEYS = List.of("Reader Mode", "Search Mode", "Session", "Tag Population");

    private final PikaConfig pikaConfig = PikaConfig.getInstance();
    private final JSONObject catalog;
    private final List<ReaderProfile> profiles;
    private final String configuredRegion;
    private volatile ReaderProfile current;
    private final Map<Integer, Integer> sessions = new ConcurrentHashMap<>();

    private static class SingletonHolder {

        private static final ProfileManager INSTANCE = new ProfileManager();
    }

    public static ProfileManager getInstance() {
        return SingletonHolder.INSTANCE;
    }

    private ProfileManager() {
        catalog = loadCatalog();

        List<ReaderProfile> list = new ArrayList<>();
        JSONObject roles = catalog.getJSONObject("roles");
        for (String id : roles.keySet()) {
            JSONObject r = roles.getJSONObject(id);
            list.add(new ReaderProfile(id, r.optString("name", id), r.optString("description", ""),
                    r.optInt("tag_population", 32), r.optInt("order", 99)));
        }
        list.sort(Comparator.comparingInt(ReaderProfile::order).thenComparing(ReaderProfile::id));
        profiles = List.copyOf(list);

        JSONObject readerConfig = pikaConfig.getKey("Reader");

        // The selected profile. Saved so a restart keeps it.
        String saved = readerConfig.optString("Profile", "");
        String fallback = catalog.optString("default_profile", FALLBACK_PROFILE);
        ReaderProfile p = find(saved).orElse(null);
        if (p == null) {
            if (!saved.isBlank()) {
                logger.warn("Unknown reader profile '{}' in the config. Using {}. Profiles: {}", saved, fallback,
                        profiles.stream().map(ReaderProfile::id).toList());
            }
            p = find(fallback).orElse(profiles.getFirst());
        }
        current = p;
        if (!p.id().equals(saved)) {
            saveSetting("Profile", p.id());
        }

        // The radio region family, or "Auto" to use the one the reader reports
        String region = readerConfig.optString("Region", "");
        if (region.isBlank()) {
            saveSetting("Region", "Auto");
            region = "Auto";
        }
        configuredRegion = parseRegion(region);

        logger.info("Reader profile: {}. Region: {}. Available profiles: {}", current,
                configuredRegion == null ? "Auto (from each reader)" : configuredRegion,
                profiles.stream().map(ReaderProfile::id).toList());
    }

    private static JSONObject loadCatalog() {
        try (InputStream in = ProfileManager.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException(RESOURCE + " is missing from the jar");
            }
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // The jar is broken. Nothing about the readers can be set up without it.
            logger.error("Unable to load the reader profiles from {}", RESOURCE, ex);
            throw new IllegalStateException("Unable to load " + RESOURCE, ex);
        }
    }

    private void saveSetting(String key, String value) {
        JSONObject readerConfig = pikaConfig.getKey("Reader");
        readerConfig.put(key, value);
        pikaConfig.putObject("Reader", readerConfig);
    }

    private static String parseRegion(String s) {
        String n = s.trim().toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");
        if (n.equals("auto")) {
            return null;
        }
        if (REGION_FAMILIES.contains(n)) {
            return n;
        }
        logger.warn("Unknown \"Region\" '{}' in the config. Using Auto (the region each reader reports). Regions: Auto, {}",
                s, String.join(", ", REGION_FAMILIES));
        return null;
    }

    /*
     * Profiles
     */
    public List<ReaderProfile> getProfiles() {
        return profiles;
    }

    public Optional<ReaderProfile> find(String s) {
        return profiles.stream().filter(p -> p.matches(s)).findFirst();
    }

    public ReaderProfile getCurrent() {
        return current;
    }

    /**
     * Select a profile and save it in the config. This does not touch the
     * readers; see ReaderHandler.setProfile().
     */
    public synchronized void setCurrent(ReaderProfile profile) {
        if (profile == null) {
            return;
        }
        logger.info("Reader profile changed from {} to {}", current, profile);
        current = profile;
        saveSetting("Profile", profile.id());
    }

    /**
     * The profile's data for one reader type, e.g. readers.impinj_r420.
     */
    public JSONObject getReaderData(String key) {
        return catalog.getJSONObject("readers").getJSONObject(key);
    }

    public JSONObject getZebraCustom() {
        return catalog.getJSONObject("zebra_llrp_custom");
    }

    /**
     * The reader's own overrides for a profile: the matching entry in its
     * "Profiles" object, or an empty object.
     */
    public JSONObject overridesFor(JSONObject readerConfig, ReaderProfile profile) {
        JSONObject all = readerConfig.optJSONObject("Profiles");
        if (all != null) {
            for (String k : all.keySet()) {
                if (profile.matches(k) && all.optJSONObject(k) != null) {
                    return all.getJSONObject(k);
                }
            }
        }
        return new JSONObject();
    }

    /**
     * Warn about config the profiles ignore: the old reader-level inventory
     * settings, overrides for unknown profiles, and unknown override keys.
     *
     * @param allowedKeys the override keys this reader type understands
     */
    public void checkReaderConfig(JSONObject readerConfig, Integer readerID, Set<String> allowedKeys) {
        for (String key : PROFILE_KEYS) {
            if (readerConfig.has(key)) {
                logger.warn("Reader {}: \"{}\" is ignored at the reader level. Profiles now set it. To override it, put it in the reader's "
                        + "\"Profiles\" entry, e.g. \"Profiles\": {\"{}\": {\"{}\": {}}}",
                        readerID, key, current.id(), key, JSONObject.valueToString(readerConfig.opt(key)));
            }
        }

        JSONObject all = readerConfig.optJSONObject("Profiles");
        if (all == null) {
            if (readerConfig.has("Profiles")) {
                logger.warn("Reader {}: \"Profiles\" must be an object, e.g. \"Profiles\": {\"finish_mats\": {\"Session\": 3}}. Ignoring it.", readerID);
            }
            return;
        }
        for (String k : all.keySet()) {
            Optional<ReaderProfile> p = find(k);
            if (p.isEmpty()) {
                logger.warn("Reader {}: \"Profiles\" has an entry for '{}', which is not a profile. Profiles: {}",
                        readerID, k, profiles.stream().map(ReaderProfile::id).toList());
                continue;
            }
            JSONObject o = all.optJSONObject(k);
            if (o == null) {
                logger.warn("Reader {}: the \"Profiles\" entry for {} must be an object. Ignoring it.", readerID, k);
                continue;
            }
            for (String key : o.keySet()) {
                if (!allowedKeys.contains(key)) {
                    logger.warn("Reader {}: unknown setting \"{}\" in the {} profile overrides. Settings: {}", readerID, key, p.get().id(), allowedKeys);
                }
            }
        }
    }

    /*
     * Sessions
     */
    /**
     * Give each reader its session. With several readers they get 2, 3, 2,
     * 3... in Index order, so neighbouring readers don't fight over the same
     * session flag. A lone reader gets 2 or 3 at random.
     *
     * @param sortedIndexes the reader Index values, in order
     */
    public void assignSessions(List<Integer> sortedIndexes) {
        sessions.clear();
        if (sortedIndexes.size() == 1) {
            int index = sortedIndexes.getFirst();
            int s = randomSession();
            sessions.put(index, s);
            logger.info("Reader {} uses session {} (picked at random from 2 and 3 since only one reader is configured)", index, s);
            return;
        }
        for (int i = 0; i < sortedIndexes.size(); i++) {
            sessions.put(sortedIndexes.get(i), i % 2 == 0 ? 2 : 3);
        }
        if (!sessions.isEmpty()) {
            logger.info("Reader sessions (Index -> session): {}", sessions);
        }
    }

    public int sessionFor(int readerIndex) {
        return sessions.computeIfAbsent(readerIndex, i -> randomSession());
    }

    private static int randomSession() {
        return ThreadLocalRandom.current().nextBoolean() ? 2 : 3;
    }

    /*
     * Regions
     */
    /**
     * @return the region family set in the config, or null for Auto
     */
    public String getConfiguredRegion() {
        return configuredRegion;
    }

    /**
     * Map a reader's region to a family.
     *
     * @param communicationsStandard the LLRP CommunicationsStandard value
     * @param txFrequencies the reader's transmit frequencies (MHz or kHz), may
     * be empty
     * @return the family, or null if it can't be told
     */
    public static String regionFamily(Integer communicationsStandard, List<Double> txFrequencies) {
        List<Double> mhz = txFrequencies == null ? List.of()
                : txFrequencies.stream().filter(f -> f != null && f > 0).map(f -> f > 10_000 ? f / 1000.0 : f).toList();
        boolean allLowBand = !mhz.isEmpty() && mhz.stream().allMatch(f -> f >= 865.0 && f <= 868.0);
        boolean anyHighBand = mhz.stream().anyMatch(f -> f > 900.0);

        int cs = communicationsStandard == null ? CommunicationsStandard.Unspecified : communicationsStandard;
        return switch (cs) {
            case CommunicationsStandard.US_FCC_Part_15 ->
                "fcc";
            case CommunicationsStandard.ETSI_302_208, CommunicationsStandard.ETSI_300_220 ->
                anyHighBand ? "etsi_hb" : allLowBand ? "etsi_lb" : null;
            case CommunicationsStandard.Japan_ARIB_STD_T89 ->
                "japan";
            case CommunicationsStandard.Australia_LIPD_1W, CommunicationsStandard.Australia_LIPD_4W,
                    CommunicationsStandard.Hong_Kong_OFTA_1049, CommunicationsStandard.Taiwan_DGT_LP0002,
                    CommunicationsStandard.Korea_MIC_Article_5_2 ->
                "global_other";
            default ->
                allLowBand ? "etsi_lb" : null;
        };
    }

    /**
     * Decide which region family to use for a reader.
     *
     * @param detected the family the reader reports (see regionFamily), or
     * null if unknown
     * @return the family, or null when it is unknown or the config contradicts
     * the reader (the caller then leaves the RF mode to the reader)
     */
    public String resolveRegion(String detected, Integer readerID, String readerDescription) {
        if (configuredRegion == null) {
            if (detected == null) {
                logger.error("Reader {}: unable to tell its region family from what it reports ({}). Set \"Region\" in the \"Reader\" config to one of {}.",
                        readerID, readerDescription, REGION_FAMILIES);
            } else {
                logger.info("Reader {}: region family {} ({})", readerID, detected, readerDescription);
            }
            return detected;
        }
        if (detected == null || detected.equals(configuredRegion)) {
            logger.info("Reader {}: region family {} from the config (reader reports {})", readerID, configuredRegion, readerDescription);
            return configuredRegion;
        }
        logger.error("Reader {}: the config sets \"Region\": \"{}\", but the reader reports {} ({}). Not applying the profile's reader mode. "
                + "Fix \"Region\" or set it to \"Auto\".", readerID, configuredRegion, detected, readerDescription);
        return null;
    }
}
