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
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The settings PikaReader changes while it runs (the reader profile, whether
 * the readers are reading), kept in their own small file next to the config
 * (.PikaReaderState.toml). Units often lose power without a shutdown, right
 * after the readers are stopped, so keeping these out of the config means the
 * config is hardly ever rewritten.
 *
 * A missing, unreadable or damaged state file never stops PikaReader: it
 * starts from the defaults and the next change writes a new file.
 */
public class PikaState {

    private static final Logger logger = LoggerFactory.getLogger(PikaState.class);

    // Each line becomes a "# ..." comment at the top of the file
    private static final String HEADER = String.join("\n",
            " PikaReader's saved state: the reader profile and whether the readers",
            " are reading. PikaReader keeps this file up to date itself. Change the",
            " profile in the web UI, and use the web UI or /start and /stop to start",
            " or stop reading. Don't edit this file while PikaReader is running.");

    private final Path file;
    private final JSONObject state;

    private static class SingletonHolder {

        private static final PikaState INSTANCE = new PikaState(PikaConfig.getInstance().getStatePath());
    }

    public static PikaState getInstance() {
        return SingletonHolder.INSTANCE;
    }

    PikaState(Path file) {
        this.file = file.toAbsolutePath();
        this.state = load(this.file);
    }

    private static JSONObject load(Path file) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JSONObject s = TomlJson.toJson(TomlJson.parse(r));
            logger.info("Read the saved state from {}: {}", file, s);
            return s;
        } catch (NoSuchFileException ex) {
            logger.info("No saved state at {} yet. Using the defaults.", file);
        } catch (IOException | RuntimeException ex) {
            // night-config's ParsingException is a RuntimeException
            logger.error("Unable to read the saved state from {}. Using the defaults (not reading). "
                    + "The file is rewritten on the next change. {}", file, String.valueOf(ex.getMessage()).strip());
        }
        return new JSONObject();
    }

    public synchronized String getString(String key, String def) {
        return state.optString(key, def);
    }

    public synchronized boolean getBoolean(String key, boolean def) {
        return state.optBoolean(key, def);
    }

    /**
     * Save a value. The file is only written when the value changes.
     */
    public synchronized void put(String key, Object value) {
        if (state.has(key) && Objects.equals(state.get(key), value)) {
            return;
        }
        state.put(key, value);
        save();
    }

    private void save() {
        try {
            AtomicFiles.write(file, w -> {
                CommentedConfig doc = TomlJson.newDocument();
                TomlJson.merge(doc, state);
                // The header goes on the first key
                doc.valueMap().keySet().stream().findFirst()
                        .ifPresent(k -> doc.setComment(List.of(k), HEADER));
                TomlJson.write(doc, w);
            });
            logger.info("Saved state to {}: {}", file, state);
        } catch (IOException ex) {
            logger.error("Unable to save the state file {}", file, ex);
        }
    }
}
