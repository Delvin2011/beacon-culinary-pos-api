package com.beaconculinary.api.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class GrvDto {
    private Long id;
    private String invoiceNumber;
    private Long purchaseOrderId;
    private String supplierName;
    private String note;
    private Long receivedById;
    private LocalDateTime receivedAt;
    private List<GrvLineDto> lines;

    // GRV VAT handling. Totals are the sum of the lines. totalExclVat and totalVat are null when
    // any line has no VAT split (legacy GRVs captured before VAT handling).
    private Boolean pricesIncludeVat;
    private BigDecimal invoiceTotalInclVat;
    private BigDecimal totalExclVat;
    private BigDecimal totalVat;
    private BigDecimal totalInclVat;
    // null when no printed total was given.
    private InvoiceCheck invoiceCheck;

    /** The printed invoice total against the computed totalInclVat. difference = printed −
     * computed. PASS within R 0.05, otherwise WARN; it never blocks the GRV. */
    public record InvoiceCheck(Status status, BigDecimal difference) {
        public enum Status {
            PASS,
            WARN
        }
    }
}
