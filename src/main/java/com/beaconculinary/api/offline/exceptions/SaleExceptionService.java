package com.beaconculinary.api.offline.exceptions;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineException;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.Detail;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.Item;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.OrderSummary;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.Page;
import com.beaconculinary.api.offline.exceptions.SaleExceptionDtos.ResolvedBy;
import com.beaconculinary.api.orders.OrderDto;
import com.beaconculinary.api.orders.OrderMapper;
import com.beaconculinary.api.orders.OrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * O4: reviewing sale exceptions (contract section 8). Resolving records who, when and why; it never
 * changes the sale (reversals use the existing refund or void). An ACCOUNT_APPROVAL_PENDING exception
 * is closed only by approve-account, which records the manager as the ACCOUNT payment's approver.
 */
@Service
public class SaleExceptionService {
    static final int MAX_NOTE = 500;
    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    private final SaleExceptionStore store;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final AuthService authService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SaleExceptionService(SaleExceptionStore store, OrderRepository orderRepository, OrderMapper orderMapper,
                                AuthService authService, JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.orderRepository = orderRepository;
        this.orderMapper = orderMapper;
        this.authService = authService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 400 field map, e.g. {"note": "..."}. */
    public static class InvalidRequest extends RuntimeException {
        private final Map<String, String> errors;

        InvalidRequest(String field, String message) {
            super(message);
            this.errors = Map.of(field, message);
        }

        public Map<String, String> getErrors() {
            return errors;
        }
    }

    @Transactional(readOnly = true)
    public Page list(String status, String severity, String code, LocalDate tradingDay, UUID deviceId, Integer page,
                     Integer size) {
        var filter = new SaleExceptionStore.Filter(
                status == null ? "OPEN" : parseStatus(status),
                severity == null ? SaleExceptionCode.Severity.REVIEW : parseSeverity(severity),
                code == null ? null : parseCode(code), tradingDay, deviceId);
        var pageNumber = page == null ? 0 : Math.max(0, page);
        var pageSize = size == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, size));
        var rows = store.list(filter, pageNumber, pageSize);
        var orders = new HashMap<Long, OrderDto>();
        orderRepository.findAllById(rows.stream().map(SaleExceptionStore.ExceptionRow::orderId).distinct().toList())
                .forEach(order -> orders.put(order.getId(), orderMapper.toDto(order)));
        var items = rows.stream().map(row -> {
            var order = orders.get(row.orderId());
            return new Item(row.id(), row.code().name(), row.severity().name(), row.status(), row.tradingDay(),
                    order == null ? null : order.getTillCode(), row.deviceId() == null ? null : row.deviceId().toString(),
                    order == null ? null : new OrderSummary(order.getId(), order.getReceiptNumber(), order.getCollectionNumber(),
                            order.getSoldAt(), order.getOriginalTotal(), order.getCashierName()),
                    json(row.detailJson()), UtcTimestamps.toInstant(row.createdAt()));
        }).toList();
        return new Page(items, pageNumber, pageSize, store.count(filter));
    }

    @Transactional(readOnly = true)
    public Detail detail(long id) {
        return detailOf(load(id));
    }

    @Transactional
    public Detail resolve(long id, String note) {
        var row = load(id);
        if (row.code() == SaleExceptionCode.ACCOUNT_APPROVAL_PENDING) {
            throw conflict("USE_APPROVE_ACCOUNT", "Approve or refund this account charge instead.");
        }
        if (!row.open()) {
            throw alreadyResolved();
        }
        var text = note == null ? "" : note.strip();
        if (text.isEmpty()) {
            throw new InvalidRequest("note", "A note is required to resolve an exception.");
        }
        if (text.length() > MAX_NOTE) {
            throw new InvalidRequest("note", "note must be 500 characters or fewer.");
        }
        var manager = authService.getCurrentUser();
        if (!store.resolve(id, SaleExceptionStore.Resolution.RESOLVED, text, manager.getId(), manager.getName(),
                UtcTimestamps.nowUtc(clock))) {
            throw alreadyResolved();
        }
        return detailOf(load(id));
    }

    @Transactional
    public Detail approveAccount(long id, String note) {
        var row = load(id);
        if (row.code() != SaleExceptionCode.ACCOUNT_APPROVAL_PENDING) {
            throw conflict("NOT_AN_ACCOUNT_APPROVAL", "Only an ACCOUNT_APPROVAL_PENDING exception can be approved.");
        }
        if (!row.open()) {
            throw alreadyResolved();
        }
        var text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > MAX_NOTE) {
            throw new InvalidRequest("note", "note must be 500 characters or fewer.");
        }
        var manager = authService.getCurrentUser();
        var accountId = json(row.detailJson()).path("accountId");
        // The manager approves the charge as an online approval would have (OrderPayment.authorizedBy).
        if (accountId.isNumber()) {
            jdbc.update("""
                    UPDATE [order_payments] SET [authorized_by] = ?
                    WHERE [order_id] = ? AND [method] = 'ACCOUNT' AND [authorized_by] IS NULL AND [account_id] = ?""",
                    manager.getId(), row.orderId(), accountId.asLong());
        } else {
            jdbc.update("""
                    UPDATE [order_payments] SET [authorized_by] = ?
                    WHERE [order_id] = ? AND [method] = 'ACCOUNT' AND [authorized_by] IS NULL AND [account_id] IS NULL""",
                    manager.getId(), row.orderId());
        }
        if (!store.resolve(id, SaleExceptionStore.Resolution.ACCOUNT_APPROVED, text, manager.getId(), manager.getName(),
                UtcTimestamps.nowUtc(clock))) {
            throw alreadyResolved();
        }
        return detailOf(load(id));
    }

    private Detail detailOf(SaleExceptionStore.ExceptionRow row) {
        var order = orderRepository.findById(row.orderId()).map(orderMapper::toDto).orElse(null);
        return new Detail(row.id(), row.code().name(), row.severity().name(), row.status(), row.tradingDay(),
                order == null ? null : order.getTillCode(), row.deviceId() == null ? null : row.deviceId().toString(),
                json(row.detailJson()), UtcTimestamps.toInstant(row.createdAt()),
                row.resolution() == null ? null : row.resolution().name(), row.resolutionNote(),
                row.resolvedBy() == null ? null : new ResolvedBy(row.resolvedBy(), row.resolvedByName()),
                UtcTimestamps.toInstant(row.resolvedAt()), order);
    }

    private SaleExceptionStore.ExceptionRow load(long id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static OfflineException conflict(String code, String message) {
        return new OfflineException(HttpStatus.CONFLICT, code, message, null);
    }

    private static OfflineException alreadyResolved() {
        return conflict("EXCEPTION_ALREADY_RESOLVED", "This exception is already resolved.");
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String parseStatus(String value) {
        if (!"OPEN".equals(value) && !"RESOLVED".equals(value)) {
            throw new InvalidRequest("status", "status must be OPEN or RESOLVED.");
        }
        return value;
    }

    private static SaleExceptionCode.Severity parseSeverity(String value) {
        try {
            return SaleExceptionCode.Severity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest("severity", "severity must be REVIEW or INFO.");
        }
    }

    private static SaleExceptionCode parseCode(String value) {
        try {
            return SaleExceptionCode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequest("code", "Unknown exception code.");
        }
    }
}
