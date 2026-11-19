package com.beaconculinary.api.inventory;

import com.beaconculinary.api.settings.SiteSettings;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * GRV VAT handling: the business is VAT-registered. The standard rate is a site setting
 * ({@code vat.standard-rate}, T0-4a; default from configuration, 0.15) and is snapshotted onto
 * every GRV line and order, so changing it affects only GRVs and orders captured afterwards.
 */
@Component
public class VatSettings {
    private final SiteSettings siteSettings;

    public VatSettings(SiteSettings siteSettings) {
        this.siteSettings = siteSettings;
    }

    public BigDecimal standardRate() {
        return siteSettings.vatStandardRate();
    }

    /** The rate a VAT code carries right now: the standard rate, or 0 for zero-rated / no VAT. */
    public BigDecimal rateFor(VatCode code) {
        return code == VatCode.STANDARD ? standardRate() : BigDecimal.ZERO;
    }
}
