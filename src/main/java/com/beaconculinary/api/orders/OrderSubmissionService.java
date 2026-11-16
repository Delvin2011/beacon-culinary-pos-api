package com.beaconculinary.api.orders;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * O1: the entry point for creating sales and adjustments. Deliberately not transactional: it sits
 * outside OrderService's and OrderAdjustmentService's transactions so it can react after one has
 * rolled back.
 *
 * <p>Duplicates are normally caught inside the transaction (an application lock on the key, then
 * a lookup). This is the backstop: if two requests with one key still both reach the insert, the
 * filtered unique index refuses the second, its transaction rolls back (stock, payments, the
 * approval token and events with it), and here the first one's record is read back and returned.
 */
@Service
@AllArgsConstructor
public class OrderSubmissionService {
    static final String CLIENT_ORDER_ID_INDEX = "UX_orders_client_order_id";
    static final String CLIENT_REQUEST_ID_INDEX = "UX_order_adjustments_client_request_id";
    static final String ORDER_NUMBER_INDEX = "IX_orders_date_number";

    private final OrderService orderService;
    private final OrderAdjustmentService orderAdjustmentService;

    public OrderResult submitOrder(CreateOrderRequest request) {
        try {
            return createOrReplay(request);
        } catch (DataIntegrityViolationException e) {
            // Only reachable if an order was written outside OrderService (old data, a manual
            // insert): the whole sale rolled back, so one clean retry is safe.
            if (!violates(e, ORDER_NUMBER_INDEX)) {
                throw e;
            }
        }
        try {
            return createOrReplay(request);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, ORDER_NUMBER_INDEX)) {
                throw new OrderNumberConflictException();
            }
            throw e;
        }
    }

    private OrderResult createOrReplay(CreateOrderRequest request) {
        try {
            return orderService.createOrder(request);
        } catch (DataIntegrityViolationException e) {
            if (request.getClientOrderId() != null && violates(e, CLIENT_ORDER_ID_INDEX)) {
                return orderService.replayOrder(request);
            }
            throw e;
        }
    }

    public OrderResult submitAdjustment(Long orderId, CreateOrderAdjustmentRequest request) {
        try {
            return orderAdjustmentService.adjustOrder(orderId, request);
        } catch (DataIntegrityViolationException e) {
            if (request.getClientRequestId() != null && violates(e, CLIENT_REQUEST_ID_INDEX)) {
                return orderAdjustmentService.replayAdjustment(orderId, request);
            }
            throw e;
        }
    }

    /** Whether the violation is of the named unique index. SQL Server names it in the message:
     * "Cannot insert duplicate key row in object 'dbo.orders' with unique index '...'". */
    static boolean violates(Throwable e, String indexName) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("'" + indexName + "'")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
