package com.beaconculinary.api.offline.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** O4: POST /pos/offline/sales request and response shapes (contract sections 2 to 4). */
public final class OfflineSaleDtos {
    private OfflineSaleDtos() {
    }

    /** Sales are read one by one (JsonNode), so one unreadable sale is INVALID instead of failing the batch. */
    public record SyncRequest(List<String> grants, List<JsonNode> sales) {
    }

    public record Sale(UUID clientOrderId, UUID grantId, Integer offlineSeq, String offlineReceiptNumber,
                       String collectionNumber, Instant soldAt, Instant tillClockAtSendUtc, Long shiftId, Long cashierId,
                       Boolean unlockedOffline, String snapshotVersion, List<Line> lines, BigDecimal subtotal,
                       BigDecimal total, BigDecimal vatRate, BigDecimal vatIncludedAmount, List<Payment> payments) {
    }

    /** lineTotal = unitPrice × quantity, without the extras (as on OrderLine). */
    public record Line(Long dailyMealOptionId, String name, String station, Integer quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal, List<Extra> extras) {
    }

    public record Extra(Long dailyComponentStockId, String name, BigDecimal priceDelta, Integer quantity,
                        BigDecimal lineTotal) {
    }

    public record Payment(String method, BigDecimal amount, BigDecimal amountTendered, BigDecimal changeDue,
                          String cardReference, Long accountId) {
    }

    public record ExceptionRef(String code, String severity) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SaleResult(@JsonInclude(JsonInclude.Include.ALWAYS) String clientOrderId, String result, Long orderId,
                             Integer orderNumber, String receiptNumber, String collectionNumber, String code,
                             String error, @JsonInclude(JsonInclude.Include.ALWAYS) List<ExceptionRef> exceptions) {
        public static SaleResult invalid(String clientOrderId, String error) {
            return new SaleResult(clientOrderId, "INVALID", null, null, null, null, null, error, List.of());
        }

        public static SaleResult refused(String clientOrderId, String error) {
            return new SaleResult(clientOrderId, "REFUSED", null, null, null, null, "GRANT_INVALID", error, List.of());
        }
    }

    public record SyncResponse(List<SaleResult> results, Instant serverTimeUtc) {
    }
}
