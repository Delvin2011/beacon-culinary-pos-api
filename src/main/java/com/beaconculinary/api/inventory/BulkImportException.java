package com.beaconculinary.api.inventory;

import java.util.List;

/** Row-level validation failure for a CSV bulk-import endpoint. Carries every row's error so
 * the caller can fix the whole sheet in one pass instead of one row at a time. */
public class BulkImportException extends RuntimeException {
    private final List<String> errors;

    public BulkImportException(List<String> errors) {
        super("Bulk import failed: " + String.join("; ", errors));
        this.errors = errors;
    }

    public List<String> getErrors() {
        return errors;
    }
}
