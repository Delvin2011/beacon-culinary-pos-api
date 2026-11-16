package com.beaconculinary.api.orders;

import com.beaconculinary.api.common.AppLock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O1: a number clash or a lock that isn't granted becomes 409 ORDER_NUMBER_CONFLICT, never a 500. */
class OrderNumberingFailureTest {
    private static DataIntegrityViolationException clash(String index) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException("Cannot insert duplicate key row in object 'dbo.orders' with unique index '" + index + "'."));
    }

    @Test
    void aNumberClash_isRetriedOnce_andTheRetryCanSucceed() {
        var orderService = mock(OrderService.class);
        var created = OrderResult.created(new OrderDto());
        when(orderService.createOrder(any()))
                .thenThrow(clash(OrderSubmissionService.ORDER_NUMBER_INDEX))
                .thenReturn(created);
        var service = new OrderSubmissionService(orderService, mock(OrderAdjustmentService.class));

        assertThat(service.submitOrder(new CreateOrderRequest())).isSameAs(created);
        verify(orderService, times(2)).createOrder(any());
    }

    @Test
    void twoNumberClashes_are409OrderNumberConflict() {
        var orderService = mock(OrderService.class);
        when(orderService.createOrder(any())).thenThrow(clash(OrderSubmissionService.ORDER_NUMBER_INDEX));
        var service = new OrderSubmissionService(orderService, mock(OrderAdjustmentService.class));

        assertThatThrownBy(() -> service.submitOrder(new CreateOrderRequest())).isInstanceOf(OrderNumberConflictException.class);
        verify(orderService, times(2)).createOrder(any());
    }

    @Test
    void otherIntegrityErrors_areNotRetried() {
        var orderService = mock(OrderService.class);
        when(orderService.createOrder(any())).thenThrow(clash("FK_something_else"));
        var service = new OrderSubmissionService(orderService, mock(OrderAdjustmentService.class));

        assertThatThrownBy(() -> service.submitOrder(new CreateOrderRequest())).isInstanceOf(DataIntegrityViolationException.class);
        verify(orderService, times(1)).createOrder(any());
    }

    @Test
    void aDayLockThatIsNotGranted_isOrderNumberConflict() {
        var appLock = mock(AppLock.class);
        doThrow(new AppLock.LockTimeoutException("timeout")).when(appLock).lock(anyString(), anyInt());
        var allocator = new OrderNumberAllocator(appLock, mock(OrderRepository.class));

        assertThatThrownBy(() -> allocator.next(LocalDate.of(2026, 11, 16))).isInstanceOf(OrderNumberConflictException.class);
    }
}
