package com.beaconculinary.api.offline.exceptions;

import com.beaconculinary.api.orders.OrderDto;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** O4: the exception review shapes (contract section 8). */
public final class SaleExceptionDtos {
    private SaleExceptionDtos() {
    }

    public record OrderSummary(long id, String receiptNumber, String collectionNumber, Instant soldAt, BigDecimal total,
                               String cashierName) {
    }

    /** One row of GET /admin/sale-exceptions. */
    public record Item(long id, String code, String severity, String status, LocalDate tradingDay, String tillCode,
                       String deviceId, OrderSummary order, JsonNode detail, Instant createdAt) {
    }

    public record Page(List<Item> items, int page, int size, long totalItems) {
    }

    public record ResolvedBy(long id, String name) {
    }

    /** GET /admin/sale-exceptions/{id}: the row, its resolution and the full order. */
    public record Detail(long id, String code, String severity, String status, LocalDate tradingDay, String tillCode,
                         String deviceId, JsonNode detail, Instant createdAt, String resolution, String resolutionNote,
                         ResolvedBy resolvedBy, Instant resolvedAt, OrderDto order) {
    }

    /** The body of resolve and approve-account. */
    public record NoteRequest(String note) {
    }
}
