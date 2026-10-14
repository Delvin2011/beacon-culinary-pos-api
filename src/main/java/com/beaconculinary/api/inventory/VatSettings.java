package com.beaconculinary.api.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * GRV VAT handling: the business is VAT-registered. The standard rate is configured
 * ({@code vat.standard-rate}, default 0.15) and snapshotted onto every GRV line, so changing it
 * affects only GRVs captured afterwards.
 */
@Component
public class VatSettings {
    private final BigDecimal standardRate;

    public VatSettings(@Value("${vat.standard-rate:0.15}") BigDecimal standardRate) {
        this.standardRate = standardRate;
    }

    public BigDecimal standardRate() {
        return standardRate;
    }

    /** The rate a VAT code carries right now: the standard rate, or 0 for zero-rated / no VAT. */
    public BigDecimal rateFor(VatCode code) {
        return code == VatCode.STANDARD ? standardRate : BigDecimal.ZERO;
    }
}
