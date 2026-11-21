package com.beaconculinary.api.auth.invite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * T0-6a: builds the invite link from {@code app.public-base-url} (APP_PUBLIC_BASE_URL), the address
 * staff open the frontend at. The token goes after '#': browsers never send that part to a server,
 * so it reaches neither the frontend host's logs nor ours. The frontend reads it and posts it in a
 * request body.
 *
 * <p>Blank base URL: no link is built (null) and the frontend builds it from its own address. A base
 * URL that isn't an absolute http(s) address without query or fragment stops the app at startup.
 */
@Component
public class InviteLinks {
    static final String PATH = "/invite#t=";

    private final String baseUrl;

    public InviteLinks(@Value("${app.public-base-url:}") String baseUrl) {
        this.baseUrl = normalizeBase(baseUrl, "app.public-base-url");
    }

    /** The link for a token, or null when no base URL is configured. */
    public String link(String token) {
        return link(baseUrl, token);
    }

    public boolean configured() {
        return baseUrl != null;
    }

    /** The link for a normalised base URL (null: no link). */
    public static String link(String normalizedBase, String token) {
        return normalizedBase == null ? null : normalizedBase + PATH + token;
    }

    /**
     * Null for blank; otherwise the URL without trailing slashes. Refuses anything that isn't an
     * absolute http(s) URL without query or fragment, naming {@code name} in the message.
     */
    public static String normalizeBase(String url, String name) {
        if (url == null || url.isBlank()) {
            return null;
        }
        var trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(name + " is not a valid URL");
        }
        var scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                || uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(name + " must be an absolute http(s) address without query or fragment, "
                    + "e.g. https://pos.example.com");
        }
        return trimmed;
    }
}
