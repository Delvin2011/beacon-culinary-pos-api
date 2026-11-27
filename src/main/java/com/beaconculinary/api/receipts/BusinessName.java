package com.beaconculinary.api.receipts;

import com.beaconculinary.api.tenancy.Customer;
import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * O2b: the business name printed on statement reports (X, Z, Cashier reading); T1-7: also the name
 * GET /public/branding answers. Read on each call, so a saved change applies to the next report; a
 * stored Z keeps the name it was built with.
 *
 * <p>T1-7: the customer's own receipt settings' businessName; while that's empty, the customer's
 * name in the customer directory (tenants.display_name, set when it was registered); with no current
 * customer (single mode) and no saved name, none. There is no platform-wide name any more (the
 * former app.business.name), so one customer's name is never shown for another.
 */
@Component
public class BusinessName {
    private final ReceiptSettingsStore store;
    private final ObjectProvider<CustomerDirectory> directory;

    public BusinessName(ReceiptSettingsStore store, ObjectProvider<CustomerDirectory> directory) {
        this.store = store;
        this.directory = directory;
    }

    /** The name to show, or null when neither the receipt settings nor the customer directory has one. */
    public String current() {
        return store.find()
                .map(ReceiptSettingsStore.Row::businessName)
                .filter(name -> !name.isBlank())
                .or(this::directoryName)
                .orElse(null);
    }

    private Optional<String> directoryName() {
        return CustomerContext.current()
                .flatMap(current -> Optional.ofNullable(directory.getIfAvailable())
                        .flatMap(found -> found.findBySlug(current.slug())))
                .map(Customer::displayName)
                .filter(name -> !name.isBlank());
    }
}
