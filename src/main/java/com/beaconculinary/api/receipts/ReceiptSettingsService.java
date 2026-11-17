package com.beaconculinary.api.receipts;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.AdminView;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.PosView;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.UpdateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O2b: the site's receipt settings (contract section 2). Text is trimmed and blank becomes null;
 * blank address lines are dropped before the 4-line limit is checked. Nothing is seeded, so every
 * value starts empty and showVatLine starts false.
 */
@Service
public class ReceiptSettingsService {
    public static final int MAX_ADDRESS_LINES = 4;
    static final int MAX_BUSINESS_NAME = 100;
    static final int MAX_ADDRESS_LINE = 100;
    static final int MAX_PHONE = 50;
    static final int MAX_VAT_NUMBER = 20;
    static final int MAX_FOOTER = 500;

    private static final ReceiptSettingsStore.Row EMPTY =
            new ReceiptSettingsStore.Row(null, List.of(), null, null, null, false, null, null);

    private final ReceiptSettingsStore store;
    private final AuthService authService;

    public ReceiptSettingsService(ReceiptSettingsStore store, AuthService authService) {
        this.store = store;
        this.authService = authService;
    }

    @Transactional(readOnly = true)
    public PosView forPos() {
        var row = current();
        return new PosView(row.businessName(), row.addressLines(), row.phone(), row.vatNumber(),
                row.footerText(), row.showVatLine());
    }

    @Transactional(readOnly = true)
    public AdminView forAdmin() {
        return adminView(current());
    }

    @Transactional
    public AdminView update(UpdateRequest request) {
        var errors = new LinkedHashMap<String, String>();
        var businessName = text(request.businessName(), MAX_BUSINESS_NAME, "businessName",
                "Business name must be 100 characters or fewer.", errors);
        var addressLines = addressLines(request.addressLines(), errors);
        var phone = text(request.phone(), MAX_PHONE, "phone", "Phone must be 50 characters or fewer.", errors);
        var vatNumber = text(request.vatNumber(), MAX_VAT_NUMBER, "vatNumber",
                "VAT number must be 20 characters or fewer.", errors);
        var footerText = text(request.footerText(), MAX_FOOTER, "footerText",
                "Footer text must be 500 characters or fewer.", errors);
        if (request.showVatLine() == null) {
            errors.put("showVatLine", "showVatLine is required.");
        }
        if (!errors.isEmpty()) {
            throw new ReceiptSettingsValidationException(errors);
        }
        var user = authService.getCurrentUser();
        store.save(businessName, addressLines, phone, vatNumber, footerText, request.showVatLine(),
                user.getId(), user.getName());
        return adminView(current());
    }

    private ReceiptSettingsStore.Row current() {
        return store.find().orElse(EMPTY);
    }

    private static AdminView adminView(ReceiptSettingsStore.Row row) {
        return new AdminView(row.businessName(), row.addressLines(), row.phone(), row.vatNumber(),
                row.footerText(), row.showVatLine(), UtcTimestamps.toInstant(row.updatedAt()), row.updatedByName());
    }

    /** Trimmed, null when blank; records an error when longer than max. */
    private static String text(String value, int max, String field, String message, Map<String, String> errors) {
        var trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > max) {
            errors.put(field, message);
        }
        return trimmed;
    }

    private static List<String> addressLines(List<String> lines, Map<String, String> errors) {
        var kept = new ArrayList<String>();
        if (lines != null) {
            for (var line : lines) {
                var trimmed = trimToNull(line);
                if (trimmed != null) {
                    kept.add(trimmed);
                }
            }
        }
        if (kept.size() > MAX_ADDRESS_LINES) {
            errors.put("addressLines", "At most 4 address lines.");
        } else if (kept.stream().anyMatch(line -> line.length() > MAX_ADDRESS_LINE)) {
            errors.put("addressLines", "Each address line must be 100 characters or fewer.");
        }
        return List.copyOf(kept);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
