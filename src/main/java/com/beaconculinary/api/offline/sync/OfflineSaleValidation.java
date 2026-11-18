package com.beaconculinary.api.offline.sync;

import com.beaconculinary.api.offline.sync.OfflineSaleDtos.Sale;

import java.math.BigDecimal;
import java.util.Set;

/**
 * O4: what makes a sale INVALID (contract section 3): a missing essential (clientOrderId, grantId,
 * soldAt, a line), a negative or missing quantity or amount, or an unknown payment method. Anything
 * else is recorded and flagged. Returns the reason, or null when the sale can be recorded.
 */
final class OfflineSaleValidation {
    static final int MAX_RECEIPT_NUMBER = 30;
    static final int MAX_COLLECTION_NUMBER = 20;
    private static final Set<String> METHODS = Set.of("CASH", "CARD", "ACCOUNT");

    private OfflineSaleValidation() {
    }

    static String problem(Sale sale) {
        if (sale.clientOrderId() == null) {
            return "clientOrderId is required.";
        }
        if (sale.grantId() == null) {
            return "grantId is required.";
        }
        if (sale.soldAt() == null) {
            return "soldAt is required.";
        }
        if (sale.lines() == null || sale.lines().isEmpty()) {
            return "At least one line is required.";
        }
        if (sale.offlineSeq() != null && sale.offlineSeq() < 1) {
            return "offlineSeq must be at least 1.";
        }
        if (tooLong(sale.offlineReceiptNumber(), MAX_RECEIPT_NUMBER) || tooLong(sale.collectionNumber(), MAX_COLLECTION_NUMBER)) {
            return "offlineReceiptNumber or collectionNumber is too long.";
        }
        if (negative(sale.subtotal()) || negative(sale.total()) || negative(sale.vatIncludedAmount()) || negative(sale.vatRate())) {
            return "Totals and VAT can't be negative.";
        }
        for (var line : sale.lines()) {
            if (line == null || line.quantity() == null || line.quantity() < 1) {
                return "Each line needs a quantity of at least 1.";
            }
            if (line.unitPrice() == null || negative(line.unitPrice()) || negative(line.lineTotal())) {
                return "Each line needs a unit price that isn't negative.";
            }
            if (line.extras() != null) {
                for (var extra : line.extras()) {
                    if (extra == null || extra.quantity() == null || extra.quantity() < 1
                            || extra.priceDelta() == null || negative(extra.priceDelta()) || negative(extra.lineTotal())) {
                        return "Each extra needs a quantity of at least 1 and a price that isn't negative.";
                    }
                }
            }
        }
        if (sale.payments() != null) {
            for (var payment : sale.payments()) {
                if (payment == null || payment.method() == null || !METHODS.contains(payment.method())) {
                    return "Each payment needs a method: CASH, CARD or ACCOUNT.";
                }
                if (payment.amount() == null || negative(payment.amount())) {
                    return "Each payment needs an amount that isn't negative.";
                }
            }
        }
        return null;
    }

    private static boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private static boolean tooLong(String value, int max) {
        return value != null && value.length() > max;
    }
}
