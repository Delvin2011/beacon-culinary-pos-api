package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.AppLock;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * O1: the day's next order number. MAX(order_number) + 1 read under an application lock on the
 * day, held until the order's transaction ends, so only one sale at a time can be between "read
 * the highest number" and "commit": concurrent sales never share a number, and a rolled-back sale
 * leaves no gap. No stored counter, so nothing can drift from the orders actually in the table.
 * Called after the stock decrements and nothing else takes this lock, so it can't deadlock.
 */
@Component
@AllArgsConstructor
public class OrderNumberAllocator {
    static final int LOCK_TIMEOUT_MILLIS = 15_000;

    private final AppLock appLock;
    private final OrderRepository orderRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public int next(LocalDate orderDate) {
        try {
            appLock.lock("order-number:" + orderDate, LOCK_TIMEOUT_MILLIS);
        } catch (AppLock.LockTimeoutException e) {
            throw new OrderNumberConflictException();
        }
        return orderRepository.findMaxOrderNumberForDate(orderDate) + 1;
    }
}
