package com.beaconculinary.api.receipts;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T1-7: the public details of the customer at the request's address, for the sign-in page and the app's header. No
 * sign-in needed. The customer comes from the address (T1-3): a suspended, unavailable or unknown address gets the
 * filter's own answer before this runs. {@code name} is {@link BusinessName#current()}, or "" when there is none;
 * {@code logoUrl} is always null for now.
 */
@RestController
public class PublicBrandingController {
    /** Five minutes in any cache; caches key on the host, so customers never share an answer. */
    static final String CACHE_CONTROL = "public, max-age=300";

    private final BusinessName businessName;

    public PublicBrandingController(BusinessName businessName) {
        this.businessName = businessName;
    }

    @GetMapping("/public/branding")
    public ResponseEntity<Branding> branding() {
        var name = businessName.current();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(new Branding(name == null ? "" : name, null));
    }

    public record Branding(String name, String logoUrl) {
    }
}
