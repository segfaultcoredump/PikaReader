/*
 * Copyright (C) 2026 John Garner <segfaultcoredump@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either VERSION 3 of the License, or
 * (at your option) any later VERSION.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.pikatimer.pikareader.conf;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Converts between the TOML config file and the {@link JSONObject} that
 * {@link PikaConfig} keeps in memory.
 *
 * The parsed TOML document is kept around and updated in place on each save
 * ({@link #merge}), so the comments and key order in the file survive the
 * rewrites PikaReader does on its own (UnitIP, Profile, Reading, ...).
 *
 * Keys are always handled as single path elements, never as dotted paths, so
 * a key such as "1.5" is not split.
 */
final class TomlJson {

    /** Keys that read best at the top of a new table. */
    private static final List<String> FIRST_KEYS = List.of("Version", "Index", "Type", "IP");

    /** The order new keys are added in: FIRST_KEYS, then alphabetical. */
    private static final Comparator<String> NEW_KEY_ORDER = Comparator
            .comparingInt((String k) -> {
                int i = FIRST_KEYS.indexOf(k);
                return i < 0 ? FIRST_KEYS.size() : i;
            })
            .thenComparing(String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Comparator.naturalOrder());

    private TomlJson() {
    }

    /** An empty, insertion-ordered TOML document. */
    static CommentedConfig newDocument() {
        return TomlFormat.newConfig(LinkedHashMap::new);
    }

    /** Parse TOML into an insertion-ordered document, comments included. */
    static CommentedConfig parse(Reader reader) {
        CommentedConfig doc = newDocument();
        new TomlParser().parse(reader, doc, ParsingMode.REPLACE);
        return doc;
    }

    /**
     * Write the document out as TOML. The parser doesn't keep blank lines, so
     * a blank line is put back in front of each comment block and section
     * header to keep the file readable. Strings are always written on one
     * line, so every line that starts with '#' or '[' is a comment or header.
     */
    static void write(UnmodifiableConfig doc, Writer writer) throws IOException {
        TomlWriter w = new TomlWriter();
        w.setIndent("");
        w.setWriteStringMultilinePredicate(s -> false);
        StringWriter raw = new StringWriter();
        w.write(doc, raw);

        StringBuilder out = new StringBuilder(raw.getBuffer().length() + 256);
        String prev = "";
        for (String line : raw.toString().split("\\R")) {
            String t = line.strip();
            if ((t.startsWith("#") || t.startsWith("["))
                    && !prev.isEmpty() && !prev.startsWith("#") && !prev.startsWith("[")) {
                out.append('\n');
            }
            out.append(line).append('\n');
            prev = t;
        }
        writer.write(out.toString());
    }

    /** The document as a JSONObject, the way org.json would have parsed it. */
    static JSONObject toJson(UnmodifiableConfig config) {
        JSONObject o = new JSONObject();
        config.valueMap().forEach((k, v) -> {
            Object j = toJsonValue(v);
            if (j != null) {
                o.put(k, j);
            }
        });
        return o;
    }

    private static Object toJsonValue(Object v) {
        return switch (v) {
            case null -> null;
            case UnmodifiableConfig c -> toJson(c);
            case List<?> l -> {
                JSONArray a = new JSONArray();
                l.forEach(e -> {
                    Object j = toJsonValue(e);
                    if (j != null) {
                        a.put(j);
                    }
                });
                yield a;
            }
            // org.json parses small whole numbers as Integer
            case Long n when n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE -> n.intValue();
            case Temporal t -> t.toString();
            default -> v;
        };
    }

    /**
     * Update the document in place so it holds the same values as the
     * JSONObject. Keys already in the document keep their place and their
     * comments, keys no longer in the JSONObject are removed, and new keys are
     * added at the end in alphabetical order.
     */
    static void merge(Config doc, JSONObject json) {
        // Drop the keys that are gone
        for (String k : new ArrayList<>(doc.valueMap().keySet())) {
            if (!json.has(k) || json.isNull(k)) {
                doc.remove(List.of(k));
            }
        }
        // Update the existing keys in document order, then add the new ones
        Set<String> newKeys = new TreeSet<>(NEW_KEY_ORDER);
        newKeys.addAll(json.keySet());
        newKeys.removeAll(doc.valueMap().keySet());
        List<String> keys = new ArrayList<>(doc.valueMap().keySet());
        keys.addAll(newKeys);
        for (String k : keys) {
            if (json.isNull(k)) {
                continue;
            }
            doc.set(List.of(k), toTomlValue(doc, doc.valueMap().get(k), json.get(k)));
        }
    }

    private static Object toTomlValue(Config parent, Object current, Object j) {
        return switch (j) {
            case JSONObject o -> {
                Config sub = current instanceof Config c ? c : parent.createSubConfig();
                merge(sub, o);
                yield sub;
            }
            case JSONArray a -> {
                List<?> old = current instanceof List<?> l ? l : List.of();
                List<Object> list = new ArrayList<>(a.length());
                for (int i = 0; i < a.length(); i++) {
                    if (a.isNull(i)) {
                        continue;
                    }
                    // Reuse the table at the same position so its comments stay
                    Object prev = i < old.size() ? old.get(i) : null;
                    list.add(toTomlValue(parent, prev, a.get(i)));
                }
                yield list;
            }
            // TOML only has 64-bit integers and doubles
            case BigInteger n -> n.longValue();
            case BigDecimal n -> n.doubleValue();
            case Float n -> n.doubleValue();
            case Number n -> n;
            case Boolean b -> b;
            case String s -> s;
            case Map<?, ?> m -> toTomlValue(parent, current, new JSONObject(m));
            case Iterable<?> it -> toTomlValue(parent, current, new JSONArray(it));
            // enums and the like
            default -> j.toString();
        };
    }
}
