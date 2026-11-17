package com.beaconculinary.api.receipts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * O2b: the business name printed on statement reports (X, Z, Cashier reading): the receipt
 * settings' businessName, or the server setting app.business.name while that's empty. Read on each
 * call, so a saved change applies to the next report; a stored Z keeps the name it was built with.
 */
@Component
public class BusinessName {
    private final ReceiptSettingsStore store;
    private final String fallback;

    public BusinessName(ReceiptSettingsStore store, @Value("${app.business.name:Beacon Culinary}") String fallback) {
        this.store = store;
        this.fallback = fallback;
    }

    public String current() {
        return store.find()
                .map(ReceiptSettingsStore.Row::businessName)
                .filter(name -> !name.isBlank())
                .orElse(fallback);
    }
}
