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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a small file so that pulling the power mid-write leaves either the
 * old file or the new one, never a partial file: write a temp file next to
 * it, force it to disk, rename it over the target, then force the directory
 * so the rename itself is on disk too.
 */
final class AtomicFiles {

    private static final Logger logger = LoggerFactory.getLogger(AtomicFiles.class);

    @FunctionalInterface
    interface WriterBody {

        void write(Writer writer) throws IOException;
    }

    private AtomicFiles() {
    }

    static void write(Path target, WriterBody body) throws IOException {
        target = target.toAbsolutePath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp.toFile());
                Writer file = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            body.write(file);
            file.flush();
            out.getFD().sync();
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // Make the rename itself durable (works on Linux)
        try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ex) {
            logger.trace("Unable to sync the directory {}", target.getParent(), ex);
        }
    }
}
