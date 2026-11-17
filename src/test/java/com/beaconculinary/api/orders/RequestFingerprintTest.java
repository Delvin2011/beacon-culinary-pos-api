package com.beaconculinary.api.orders;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** O1: what counts as "the same request" for an idempotency key, and how the backstop recognises
 * a duplicate key from SQL Server's message. */
class RequestFingerprintTest {

    private static OrderLineExtraRequest extra(long stockId, int quantity) {
        var extra = new OrderLineExtraRequest();
        extra.setDailyComponentStockId(stockId);
        extra.setQuantity(quantity);
        return extra;
    }

    private static OrderPaymentRequest payment(PaymentMethod method, String amount, String tendered, String cardReference) {
        var payment = new OrderPaymentRequest();
        payment.setMethod(method);
        payment.setAmount(new BigDecimal(amount));
        payment.setAmountTendered(tendered == null ? null : new BigDecimal(tendered));
        payment.setCardReference(cardReference);
        return payment;
    }

    private static CreateOrderRequest sale(List<OrderLineExtraRequest> extras, List<OrderPaymentRequest> payments) {
        var line = new OrderLineRequest();
        line.setDailyMealOptionId(7L);
        line.setQuantity(2);
        line.setExtras(new ArrayList<>(extras));
        var request = new CreateOrderRequest();
        request.setLines(List.of(line));
        request.setPayments(new ArrayList<>(payments));
        return request;
    }

    private static boolean same(CreateOrderRequest a, CreateOrderRequest b) {
        return RequestFingerprint.matches(RequestFingerprint.ofOrder(a), RequestFingerprint.ofOrder(b));
    }

    @Test
    void numbersMatchWhateverTheirScale_andTextIsTrimmed() {
        var a = sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CASH, "50", "100", null),
                payment(PaymentMethod.CARD, "80.5", null, " 1234 ")));
        var b = sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CASH, "50.00", "100.00", null),
                payment(PaymentMethod.CARD, "80.50", null, "1234")));
        assertThat(same(a, b)).isTrue();
    }

    @Test
    void theOrderOfPaymentsAndOfExtrasDoesNotMatter() {
        var a = sale(List.of(extra(1, 1), extra(2, 3)), List.of(payment(PaymentMethod.CASH, "50", "50", null),
                payment(PaymentMethod.CARD, "80", null, null)));
        var b = sale(List.of(extra(2, 3), extra(1, 1)), List.of(payment(PaymentMethod.CARD, "80", null, null),
                payment(PaymentMethod.CASH, "50", "50", null)));
        assertThat(same(a, b)).isTrue();
    }

    @Test
    void aBlankCardReferenceCountsAsNone() {
        var a = sale(List.of(), List.of(payment(PaymentMethod.CARD, "50", null, "  ")));
        var b = sale(List.of(), List.of(payment(PaymentMethod.CARD, "50", null, null)));
        assertThat(same(a, b)).isTrue();
    }

    @Test
    void authorizationTokensAreLeftOut() {
        var a = sale(List.of(), List.of(payment(PaymentMethod.CASH, "45", "50", null)));
        var b = sale(List.of(), List.of(payment(PaymentMethod.CASH, "45", "50", null)));
        a.setDiscount(discount("first-token"));
        b.setDiscount(discount("second-token"));
        assertThat(same(a, b)).isTrue();
    }

    private static SaleDiscountRequest discount(String token) {
        var discount = new SaleDiscountRequest();
        discount.setType("FIXED_AMOUNT");
        discount.setValue(new BigDecimal("5"));
        discount.setReasonCode("STAFF");
        var authorization = new SaleDiscountRequest.Authorization();
        authorization.setAuthorizationToken(token);
        discount.setAuthorization(authorization);
        return discount;
    }

    @Test
    void whatWasSoldOrPaidChangesTheFingerprint() {
        var base = sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CASH, "50", "100", null)));
        assertThat(same(base, sale(List.of(extra(1, 2)), List.of(payment(PaymentMethod.CASH, "50", "100", null))))).isFalse();
        assertThat(same(base, sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CASH, "50", "60", null))))).isFalse();
        assertThat(same(base, sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CARD, "50", null, null))))).isFalse();
        var discounted = sale(List.of(extra(1, 1)), List.of(payment(PaymentMethod.CASH, "50", "100", null)));
        discounted.setDiscount(discount("token"));
        assertThat(same(base, discounted)).isFalse();
    }

    @Test
    void anAdjustmentDependsOnItsOrderAndReason() {
        var request = new CreateOrderAdjustmentRequest();
        request.setScope(OrderAdjustmentScope.WHOLE_ORDER);
        request.setReasonCode(OrderAdjustmentReasonCode.CUSTOMER_COMPLAINT);
        request.setAuthorizationToken("a");
        var retry = new CreateOrderAdjustmentRequest();
        retry.setScope(OrderAdjustmentScope.WHOLE_ORDER);
        retry.setReasonCode(OrderAdjustmentReasonCode.CUSTOMER_COMPLAINT);
        retry.setSessionToken("b");

        assertThat(RequestFingerprint.matches(RequestFingerprint.ofAdjustment(1L, request),
                RequestFingerprint.ofAdjustment(1L, retry))).isTrue();
        assertThat(RequestFingerprint.matches(RequestFingerprint.ofAdjustment(1L, request),
                RequestFingerprint.ofAdjustment(2L, retry))).isFalse();
        retry.setReasonCode(OrderAdjustmentReasonCode.KITCHEN_ERROR);
        assertThat(RequestFingerprint.matches(RequestFingerprint.ofAdjustment(1L, request),
                RequestFingerprint.ofAdjustment(1L, retry))).isFalse();
    }

    @Test
    void aStoredNullHashNeverMatches() {
        assertThat(RequestFingerprint.matches(null, new byte[32])).isFalse();
    }

    @Test
    void theBackstopRecognisesOnlyItsOwnIndex() {
        var duplicateKey = new DataIntegrityViolationException("could not execute statement", new SQLException(
                "Cannot insert duplicate key row in object 'dbo.orders' with unique index 'UX_orders_client_order_id'. "
                        + "The duplicate key value is (8c1d...)."));
        var otherIndex = new DataIntegrityViolationException("could not execute statement", new SQLException(
                "Cannot insert duplicate key row in object 'dbo.orders' with unique index 'IX_orders_date_number'."));

        assertThat(OrderSubmissionService.violates(duplicateKey, OrderSubmissionService.CLIENT_ORDER_ID_INDEX)).isTrue();
        assertThat(OrderSubmissionService.violates(otherIndex, OrderSubmissionService.CLIENT_ORDER_ID_INDEX)).isFalse();
    }
}
