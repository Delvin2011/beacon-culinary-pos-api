package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * T0-6a: builds the invite link. The token goes after '#': browsers never send that part to a
 * server, so it reaches neither the frontend host's logs nor ours. The frontend reads it and posts
 * it in a request body.
 *
 * <p>T1-7: the link is on the current customer's primary host from the customer directory,
 * {@code https://<primary host>/invite#t=<token>} (locally https://&lt;slug&gt;.pos.localhost). With
 * no current customer (single mode) or no primary host, no link is built (null) and the frontend
 * builds it from its own address. There is no configured base URL any more.
 */
@Component
public class InviteLinks {
    static final String PATH = "/invite#t=";

    private final ObjectProvider<CustomerDirectory> directory;

    public InviteLinks(ObjectProvider<CustomerDirectory> directory) {
        this.directory = directory;
    }

    /** The link for a token on the current customer's primary host, or null (see the class comment). */
    public String link(String token) {
        return CustomerContext.current()
                .flatMap(current -> Optional.ofNullable(directory.getIfAvailable())
                        .flatMap(found -> found.findBySlug(current.slug())))
                .map(Customer::primaryHost)
                .map(host -> link("https://" + host, token))
                .orElse(null);
    }

    /** The link for a normalised base URL (null: no link). */
    public static String link(String normalizedBase, String token) {
        return normalizedBase == null ? null : normalizedBase + PATH + token;
    }

    /**
     * Null for blank; otherwise the URL without trailing slashes. Refuses anything that isn't an
     * absolute http(s) URL without query or fragment, naming {@code name} in the message. Used by
     * {@code FirstAdminCommand --base-url}.
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
