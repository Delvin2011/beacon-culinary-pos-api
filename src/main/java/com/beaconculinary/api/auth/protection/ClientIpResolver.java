package com.beaconculinary.api.auth.protection;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Works out the client IP for login protection (decision D7).
 *
 * <p>By default the client IP is {@code request.getRemoteAddr()}. Only when the direct peer is a
 * configured trusted proxy is X-Forwarded-For used: the right-most entry that is not itself a
 * trusted proxy. Entries further left are written by the client and can be forged, so the search
 * stops at the first malformed entry and falls back to the peer address. Nothing here performs a
 * DNS lookup: only IP literals are ever passed to {@link IpAddressMatcher}.
 */
@Component
public class ClientIpResolver {
    static final int MAX_IP_LENGTH = 45;
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<IpAddressMatcher> trustedProxies;

    @org.springframework.beans.factory.annotation.Autowired
    public ClientIpResolver(LoginProtectionProperties properties) {
        this(properties.getTrustedProxies());
    }

    ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream().map(String::trim).map(IpAddressMatcher::new).toList();
    }

    /** The client IP, or null when the peer address is missing or malformed. */
    public String resolve(String remoteAddr, String forwardedFor) {
        var peer = normalise(remoteAddr);
        if (peer == null) {
            return null;
        }
        if (forwardedFor == null || forwardedFor.isBlank() || !isTrusted(peer)) {
            return peer;
        }
        var entries = forwardedFor.split(",");
        for (int i = entries.length - 1; i >= 0; i--) {
            var candidate = normalise(entries[i]);
            if (candidate == null) {
                return peer;
            }
            if (!isTrusted(candidate)) {
                return candidate;
            }
        }
        return peer;
    }

    private boolean isTrusted(String ip) {
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(ip));
    }

    /**
     * An IP literal in canonical text form, or null. Accepts IPv4, IPv6, "[IPv6]:port" and
     * "IPv4:port" (some proxies add the port); anything else, including host names, is rejected.
     */
    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        var value = raw.trim();
        if (value.isEmpty() || value.length() > 64) {
            return null;
        }
        if (value.startsWith("[")) {
            var end = value.indexOf(']');
            if (end < 0) {
                return null;
            }
            value = value.substring(1, end);
        } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            value = value.substring(0, value.indexOf(':'));
        }
        if (IPV4.matcher(value).matches()) {
            return value;
        }
        if (value.contains(":") && IPV6_CHARS.matcher(value).matches() && value.length() <= MAX_IP_LENGTH) {
            try {
                // A literal containing ':' is parsed, never resolved through DNS.
                var address = java.net.InetAddress.getByName(value);
                return address instanceof java.net.Inet6Address ? value.toLowerCase(Locale.ROOT) : null;
            } catch (java.net.UnknownHostException | SecurityException e) {
                return null;
            }
        }
        return null;
    }

    /** True for an IP literal or "IP/prefix" with a prefix in range. Used to validate trusted-proxies. */
    static boolean isValidProxyEntry(String entry) {
        if (entry == null) {
            return false;
        }
        var parts = entry.trim().split("/", -1);
        if (parts.length > 2) {
            return false;
        }
        var address = parts[0];
        boolean ipv4 = IPV4.matcher(address).matches();
        if (!ipv4 && (normalise(address) == null || !address.contains(":"))) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        try {
            var prefix = Integer.parseInt(parts[1]);
            return prefix >= 0 && prefix <= (ipv4 ? 32 : 128);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
