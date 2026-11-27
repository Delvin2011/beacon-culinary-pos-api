package com.beaconculinary.api.tenancy;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T1-1: a request's host as the platform database stores it: trimmed, lower-case, without a port and without a
 * trailing dot. Anything that isn't a plain host name (an IP literal in brackets, spaces, an empty label, more than 253
 * characters) gives no host, so it can't match a customer.
 */
public final class HostNames {
    /** Labels of letters, digits and inner hyphens, joined by single dots. */
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");
    private static final int MAX_LENGTH = 253;

    private HostNames() {
    }

    /** E.g. "Acme.POS.optimumscs.com.:443" is "acme.pos.optimumscs.com"; "[::1]:443" is empty. */
    public static Optional<String> normalise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var host = raw.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return Optional.empty();
        }
        var colon = host.indexOf(':');
        if (colon >= 0) {
            if (colon != host.lastIndexOf(':') || !PORT.matcher(host.substring(colon + 1)).matches()) {
                return Optional.empty();
            }
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.length() > MAX_LENGTH || !HOST.matcher(host).matches()) {
            return Optional.empty();
        }
        return Optional.of(host);
    }
}
