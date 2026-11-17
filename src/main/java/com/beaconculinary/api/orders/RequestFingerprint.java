package com.beaconculinary.api.orders;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * O1: what "the same request" means for an idempotency key. A SHA-256 over a normalised JSON form
 * of the fields that decide what was sold and paid, so a retry of the same sale matches and a
 * reused key with a different sale doesn't.
 *
 * <p>Sale: lines in request order (dailyMealOptionId, quantity, extras sorted by
 * dailyComponentStockId then quantity); payments sorted by method (method, amount, amountTendered,
 * cardReference, accountId); discount (type, value, reasonCode, note); complimentary (reasonCode,
 * note). Adjustment: orderId, requestedAction, scope, reasonCode, note, discountType, discountValue.
 *
 * <p>Normalised: numbers ignore trailing zeros (12.5 = 12.50), text is trimmed and blank counts as
 * absent. Left out: authorization tokens, because a retry may carry a fresh one or one already used.
 * The "v" field versions the definition; changing it means old keys no longer match.
 */
final class RequestFingerprint {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private RequestFingerprint() {
    }

    static byte[] ofOrder(CreateOrderRequest request) {
        var root = new TreeMap<String, Object>();
        root.put("v", 1);
        root.put("lines", lines(request.getLines()));
        root.put("payments", payments(request.getPayments()));
        var discount = request.getDiscount();
        root.put("discount", discount == null ? null : map(
                "type", text(discount.getType()),
                "value", decimal(discount.getValue()),
                "reasonCode", text(discount.getReasonCode()),
                "note", text(discount.getNote())));
        var complimentary = request.getComplimentary();
        root.put("complimentary", complimentary == null ? null : map(
                "reasonCode", text(complimentary.getReasonCode()),
                "note", text(complimentary.getNote())));
        return sha256(root);
    }

    static byte[] ofAdjustment(Long orderId, CreateOrderAdjustmentRequest request) {
        var root = new TreeMap<String, Object>();
        root.put("v", 1);
        root.put("orderId", orderId);
        root.put("requestedAction", name(request.getRequestedAction()));
        root.put("scope", name(request.getScope()));
        root.put("reasonCode", name(request.getReasonCode()));
        root.put("note", text(request.getNote()));
        root.put("discountType", name(request.getDiscountType()));
        root.put("discountValue", decimal(request.getDiscountValue()));
        return sha256(root);
    }

    static boolean matches(byte[] stored, byte[] presented) {
        return stored != null && MessageDigest.isEqual(stored, presented);
    }

    private static List<Object> lines(List<OrderLineRequest> lines) {
        var result = new ArrayList<Object>();
        if (lines == null) {
            return result;
        }
        for (var line : lines) {
            var extras = new ArrayList<OrderLineExtraRequest>(line.getExtras() == null ? List.of() : line.getExtras());
            extras.sort(Comparator.comparing(OrderLineExtraRequest::getDailyComponentStockId,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(OrderLineExtraRequest::getQuantity, Comparator.nullsFirst(Comparator.naturalOrder())));
            result.add(map(
                    "dailyMealOptionId", line.getDailyMealOptionId(),
                    "quantity", line.getQuantity(),
                    "extras", extras.stream().map(extra -> map(
                            "dailyComponentStockId", extra.getDailyComponentStockId(),
                            "quantity", extra.getQuantity())).toList()));
        }
        return result;
    }

    private static List<Object> payments(List<OrderPaymentRequest> payments) {
        if (payments == null) {
            return List.of();
        }
        var sorted = new ArrayList<>(payments);
        sorted.sort(Comparator.comparing(payment -> String.valueOf(payment.getMethod())));
        return sorted.stream().map(payment -> (Object) map(
                "method", name(payment.getMethod()),
                "amount", decimal(payment.getAmount()),
                "amountTendered", decimal(payment.getAmountTendered()),
                "cardReference", text(payment.getCardReference()),
                "accountId", payment.getAccountId())).toList();
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static byte[] sha256(Object value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Couldn't fingerprint the request.", e);
        }
    }
}
