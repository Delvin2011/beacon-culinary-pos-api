package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
public class BulkGrvImportResultDto {
    private int created;
    private List<GrvDto> grvs;
    // GRV VAT handling: one summary per invoice (= per GRV), to compare with the paper invoices.
    private List<InvoiceSummary> invoices;

    public record InvoiceSummary(String invoiceNumber, int lineCount, boolean pricesIncludeVat, BigDecimal totalExclVat,
                                 BigDecimal totalVat, BigDecimal totalInclVat, GrvDto.InvoiceCheck invoiceCheck) {
    }
}
