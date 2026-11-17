package com.beaconculinary.api.receipts;

import java.util.Map;

/** O2b: a PUT /admin/receipt-settings value was refused; rendered as a 400 field map. */
public class ReceiptSettingsValidationException extends RuntimeException {
    private final Map<String, String> errors;

    public ReceiptSettingsValidationException(Map<String, String> errors) {
        super("Invalid receipt settings");
        this.errors = Map.copyOf(errors);
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
