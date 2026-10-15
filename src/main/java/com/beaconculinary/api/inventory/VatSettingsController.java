package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/** GRV VAT handling §3: the configured standard rate, so the GRV screen can preview VAT before
 * saving. ADMIN and STOCK_ADMIN (see AdminSecurityRules). */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/settings/vat")
public class VatSettingsController {
    private final VatSettings vatSettings;

    public record VatSettingsDto(BigDecimal standardRate) {
    }

    @GetMapping
    public VatSettingsDto get() {
        return new VatSettingsDto(vatSettings.standardRate());
    }
}
