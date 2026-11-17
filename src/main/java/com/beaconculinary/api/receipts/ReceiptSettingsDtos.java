package com.beaconculinary.api.receipts;

import java.time.Instant;
import java.util.List;

/** O2b: the receipt settings shapes (contract section 2). */
public final class ReceiptSettingsDtos {
    private ReceiptSettingsDtos() {
    }

    /** GET /pos/receipt-settings: what the till prints. */
    public record PosView(String businessName, List<String> addressLines, String phone, String vatNumber,
                          String footerText, boolean showVatLine) {
    }

    /** GET and PUT /admin/receipt-settings: the print values plus when and by whom they were last saved. */
    public record AdminView(String businessName, List<String> addressLines, String phone, String vatNumber,
                            String footerText, boolean showVatLine, Instant updatedAt, String updatedBy) {
    }

    /** PUT /admin/receipt-settings: replaces every value. showVatLine is required. */
    public record UpdateRequest(String businessName, List<String> addressLines, String phone, String vatNumber,
                                String footerText, Boolean showVatLine) {
    }
}
