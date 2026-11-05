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
package com.pikatimer.pikareader.readers;

import java.security.Security;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLRP over TLS settings shared by the Impinj and Zebra drivers.
 *
 * The LLRP toolkit's TLS (LLRPConnector.connect(timeout, true)) already
 * trusts any certificate and skips the hostname check, so self-signed,
 * expired and wrong-hostname reader certificates are accepted. The JDK still
 * rejects certificates and TLS 1.2 settings that use algorithms it considers
 * weak, which older reader firmware may use. The readers are cabled directly
 * to the PikaReader computer, so allowLegacyCertificates() turns those checks off too.
 *
 * @author John Garner <segfaultcoredump@gmail.com>
 */
public final class LlrpTls {

    private static final Logger logger = LoggerFactory.getLogger(LlrpTls.class);

    /** IANA port for plain LLRP */
    public static final int PORT = 5084;
    /** IANA port for LLRP over TLS */
    public static final int TLS_PORT = 5085;

    private static boolean relaxed = false;

    private LlrpTls() {
    }

    /**
     * "TLS": true turns on LLRP over TLS. Defaults to false.
     */
    public static boolean useTls(JSONObject config) {
        return config.optBoolean("TLS", false);
    }

    /**
     * "Port": defaults to 5085 with TLS, otherwise 5084.
     */
    public static int port(JSONObject config, boolean tls) {
        return config.optInt("Port", tls ? TLS_PORT : PORT);
    }

    /**
     * Let the JDK accept any reader certificate and the reader's TLS 1.2
     * algorithms, however old. This applies to the whole JVM, which only uses
     * TLS for LLRP (the web UI is plain HTTP and RShell uses SSH).
     *
     * Must run before the first TLS connection, because the JDK reads these
     * properties once. Safe to call more than once.
     */
    public static synchronized void allowLegacyCertificates() {
        if (relaxed) {
            return;
        }
        Security.setProperty("jdk.certpath.disabledAlgorithms", "");
        Security.setProperty("jdk.tls.disabledAlgorithms", "");
        relaxed = true;
        logger.warn("LLRP over TLS is enabled. Reader certificates are not checked and legacy TLS algorithms are allowed, "
                + "because the readers are expected to be directly connected to PikaReader.");
    }
}
