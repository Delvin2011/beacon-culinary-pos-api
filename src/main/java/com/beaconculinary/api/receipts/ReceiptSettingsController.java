package com.beaconculinary.api.receipts;

import com.beaconculinary.api.receipts.ReceiptSettingsDtos.AdminView;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.PosView;
import com.beaconculinary.api.receipts.ReceiptSettingsDtos.UpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * O2b: receipt settings. /pos/receipt-settings is for CASHIER and ADMIN (PosSecurityRules);
 * /admin/receipt-settings is ADMIN only (the /admin/** rule in AdminSecurityRules).
 */
@RestController
public class ReceiptSettingsController {
    private final ReceiptSettingsService service;

    public ReceiptSettingsController(ReceiptSettingsService service) {
        this.service = service;
    }

    @GetMapping("/pos/receipt-settings")
    public PosView forPos() {
        return service.forPos();
    }

    @GetMapping("/admin/receipt-settings")
    public AdminView forAdmin() {
        return service.forAdmin();
    }

    @PutMapping("/admin/receipt-settings")
    public AdminView update(@RequestBody UpdateRequest request) {
        return service.update(request);
    }
}
