/*
 * Copyright (C) 2024 John Garner <segfaultcoredump@gmail.com>
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
package com.pikatimer.pikareader.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.TriggeringPolicyBase;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Triggers exactly one rollover, on the first logging event after startup, so
 * every run of the application begins with a fresh log file.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public class StartupTriggeringPolicy extends TriggeringPolicyBase<ILoggingEvent> {

    private final AtomicBoolean fired = new AtomicBoolean(false);

    @Override
    public boolean isTriggeringEvent(File activeFile, ILoggingEvent event) {
        return fired.compareAndSet(false, true);
    }
}
