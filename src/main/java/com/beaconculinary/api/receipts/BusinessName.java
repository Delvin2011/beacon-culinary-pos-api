package com.beaconculinary.api.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O2b: the business name printed on statement reports (X, Z, Cashier reading): the receipt
 * settings' businessName, or the platform setting app.business.name while that's empty. Read on each
 * call, so a saved change applies to the next report; a stored Z keeps the name it was built with.
 *
 * <p>T0-4a: app.business.name is blank by default (env APP_BUSINESS_NAME), so one customer's name is
 * never printed on another's reports. With both blank, reports print no business name; onboarding
 * sets the customer's name in the receipt settings.
 */
@Component
public class BusinessName {
    private final ReceiptSettingsStore store;
    private final String fallback;

    public BusinessName(ReceiptSettingsStore store, @Value("${app.business.name:}") String fallback) {
        this.store = store;
        this.fallback = fallback == null || fallback.isBlank() ? null : fallback.trim();
    }

    /** The name to print, or null when neither the receipt settings nor app.business.name has one. */
    public String current() {
        return store.find()
                .map(ReceiptSettingsStore.Row::businessName)
                .filter(name -> !name.isBlank())
                .orElse(fallback);
    }
}
