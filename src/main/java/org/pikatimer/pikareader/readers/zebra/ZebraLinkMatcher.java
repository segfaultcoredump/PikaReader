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
package org.pikatimer.pikareader.readers.zebra;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llrp.ltk.generated.enumerations.C1G2SpectralMaskIndicator;
import org.llrp.ltk.generated.parameters.C1G2UHFRFModeTableEntry;

/**
 * Matches the link classes in reader-profiles.json (readers.zebra_fx
 * .link_classes) against the RF mode table a Zebra reader reports in
 * GET_READER_CAPABILITIES.
 *
 * Each link class is a ranked list of candidate links. A candidate matches a
 * mode table entry when the Miller value, BDR and PIE are equal and the
 * candidate's Tari is inside the entry's Tari range and on its step grid (a
 * step of 0 means the Tari must equal the minimum). The first candidate with
 * a match wins. Since the reader's own table decides what is available, the
 * same profile works in every region.
 *
 * Older readers (e.g. the FX7400, built around 250 and 300 kHz links) may
 * match no candidate exactly. Then the class's "nearest" rule picks the
 * closest entry with the right Miller value: by BLF, then spectral mask, then
 * PIE preference, then the Tari on the entry's grid (see nearest_rule_order
 * in reader-profiles.json).
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public final class ZebraLinkMatcher {

    /**
     * A resolved link: the mode table entry for C1G2RFControl.ModeIndex and
     * the Tari (ns) for C1G2RFControl.Tari.
     *
     * @param nearest true when the class's nearest rule picked it, since no
     * candidate matched exactly
     */
    public record ZebraLink(String linkClass, long modeId, int tariNs, C1G2UHFRFModeTableEntry entry, boolean nearest) {

        boolean sameLink(ZebraLink other) {
            return other != null && modeId == other.modeId && tariNs == other.tariNs;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "mode %d @ Tari %.2f us%s", modeId, tariNs / 1000.0,
                    linkClass == null ? "" : " (" + linkClass + (nearest ? ", nearest rule" : "") + ")");
        }
    }

    private ZebraLinkMatcher() {
    }

    /**
     * @param name a link class name, matched ignoring case, spaces, dashes
     * and underscores
     * @return the link class key in linkClasses, if there is one
     */
    public static Optional<String> findClass(JSONObject linkClasses, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = normalize(name);
        return linkClasses.keySet().stream().filter(k -> normalize(k).equals(n)).findFirst();
    }

    /**
     * @param automacIndex the reader's automac mode index, which must never
     * be used, or null if unknown
     * @return the first candidate of the class the reader's table offers, or
     * else the entry the class's nearest rule picks. Empty when the reader
     * has no entry with the class's Miller value.
     */
    public static Optional<ZebraLink> resolve(String linkClass, JSONObject linkClasses, List<C1G2UHFRFModeTableEntry> table, Long automacIndex) {
        JSONObject cls = linkClasses.optJSONObject(linkClass);
        if (cls == null || table == null) {
            return Optional.empty();
        }
        JSONArray candidates = cls.getJSONArray("candidates");
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject candidate = candidates.getJSONObject(i);
            for (C1G2UHFRFModeTableEntry e : table) {
                long id = e.getModeIdentifier().toLong();
                if (automacIndex != null && id == automacIndex) {
                    // Variable link parameters: never a profile's link
                    continue;
                }
                if (matches(candidate, e)) {
                    return Optional.of(new ZebraLink(linkClass, id, candidate.getInt("tari_ns"), e, false));
                }
            }
        }
        JSONObject nearest = cls.optJSONObject("nearest");
        return nearest == null ? Optional.empty() : nearest(linkClass, nearest, table, automacIndex);
    }

    /**
     * The nearest rule, for tables where no candidate matches exactly. Only
     * entries with the rule's Miller value are considered (never automac),
     * ranked by:
     * <ol>
     * <li>BLF closest to blf_khz, or the highest for "highest"; ties go to
     * the lower BLF</li>
     * <li>spectral mask: dense interrogator, then multi, then single or
     * unknown</li>
     * <li>pie_preference order</li>
     * <li>the Tari on the entry's grid closest to tari_ns, or the shortest
     * for "shortest"; ties go to the longer Tari</li>
     * </ol>
     */
    static Optional<ZebraLink> nearest(String linkClass, JSONObject rule, List<C1G2UHFRFModeTableEntry> table, Long automacIndex) {
        int m = rule.getInt("m");
        boolean highestBlf = "highest".equals(rule.optString("blf_khz"));
        long targetBlf = highestBlf ? 0 : Math.round(rule.getDouble("blf_khz") * 1000);
        boolean shortestTari = "shortest".equals(rule.optString("tari_ns"));
        long targetTari = shortestTari ? 0 : rule.getLong("tari_ns");
        List<Long> pies = rule.optJSONArray("pie_preference", new JSONArray()).toList().stream()
                .map(o -> ((Number) o).longValue()).toList();

        Comparator<C1G2UHFRFModeTableEntry> order = highestBlf
                ? Comparator.comparingLong((C1G2UHFRFModeTableEntry e) -> ZebraReaderMode.blf(e)).reversed()
                : Comparator.comparingLong((C1G2UHFRFModeTableEntry e) -> Math.abs(ZebraReaderMode.blf(e) - targetBlf))
                        .thenComparingLong(ZebraReaderMode::blf);
        order = order.thenComparingInt(ZebraLinkMatcher::maskRank);
        order = order.thenComparingInt(e -> {
            int i = pies.indexOf(value(e.getPIEValue() == null ? null : e.getPIEValue().toLong()));
            return i < 0 ? pies.size() : i;
        });
        order = shortestTari
                ? order.thenComparingLong(e -> gridTari(e, 0, true))
                : order.thenComparingLong((C1G2UHFRFModeTableEntry e) -> Math.abs(gridTari(e, targetTari, false) - targetTari))
                        .thenComparing(Comparator.comparingLong((C1G2UHFRFModeTableEntry e) -> gridTari(e, targetTari, false)).reversed());
        order = order.thenComparingLong(e -> e.getModeIdentifier().toLong());

        return table.stream()
                .filter(e -> ZebraReaderMode.millerFactor(e) == m)
                .filter(e -> automacIndex == null || e.getModeIdentifier().toLong() != automacIndex)
                .min(order)
                .map(e -> new ZebraLink(linkClass, e.getModeIdentifier().toLong(), (int) gridTari(e, targetTari, shortestTari), e, true));
    }

    /**
     * @return the Tari (ns) on the entry's grid closest to the target (ties
     * go to the longer Tari), or the entry's shortest Tari
     */
    static long gridTari(C1G2UHFRFModeTableEntry e, long target, boolean shortest) {
        long min = Math.max(0, value(e.getMinTariValue() == null ? null : e.getMinTariValue().toLong()));
        long max = Math.max(min, value(e.getMaxTariValue() == null ? null : e.getMaxTariValue().toLong()));
        long step = value(e.getStepTariValue() == null ? null : e.getStepTariValue().toLong());
        if (shortest || step <= 0 || target <= min) {
            return min;
        }
        long steps = (max - min) / step;
        long lower = Math.min(steps, (target - min) / step);
        long upper = Math.min(steps, lower + 1);
        long lowTari = min + lower * step;
        long highTari = min + upper * step;
        return Math.abs(target - lowTari) < Math.abs(highTari - target) ? lowTari : highTari;
    }

    // Dense interrogator first, then multi, then single or unknown
    private static int maskRank(C1G2UHFRFModeTableEntry e) {
        if (e.getSpectralMaskIndicator() == null) {
            return 2;
        }
        return switch (e.getSpectralMaskIndicator().intValue()) {
            case C1G2SpectralMaskIndicator.DI ->
                0;
            case C1G2SpectralMaskIndicator.MI ->
                1;
            default ->
                2;
        };
    }

    /**
     * Resolve the fallback classes, dropping classes with no match and links
     * that repeat the primary or an earlier fallback.
     */
    public static List<ZebraLink> resolveFallbacks(List<String> classes, JSONObject linkClasses, List<C1G2UHFRFModeTableEntry> table,
            Long automacIndex, ZebraLink primary) {
        List<ZebraLink> result = new ArrayList<>();
        for (String c : classes) {
            resolve(c, linkClasses, table, automacIndex).ifPresent(link -> {
                if (!link.sameLink(primary) && result.stream().noneMatch(link::sameLink)) {
                    result.add(link);
                }
            });
        }
        return result;
    }

    static boolean matches(JSONObject candidate, C1G2UHFRFModeTableEntry e) {
        if (ZebraReaderMode.millerFactor(e) != candidate.getInt("m")) {
            return false;
        }
        if (value(e.getBDRValue() == null ? null : e.getBDRValue().toLong()) != candidate.getLong("bdr_bps")) {
            return false;
        }
        if (value(e.getPIEValue() == null ? null : e.getPIEValue().toLong()) != candidate.getLong("pie")) {
            return false;
        }
        long tari = candidate.getLong("tari_ns");
        long min = value(e.getMinTariValue() == null ? null : e.getMinTariValue().toLong());
        long max = value(e.getMaxTariValue() == null ? null : e.getMaxTariValue().toLong());
        long step = value(e.getStepTariValue() == null ? null : e.getStepTariValue().toLong());
        if (step == 0) {
            return tari == min;
        }
        return tari >= min && tari <= max && (tari - min) % step == 0;
    }

    private static long value(Long v) {
        return v == null ? -1 : v;
    }

    private static String normalize(String s) {
        return s.trim().replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }
}
