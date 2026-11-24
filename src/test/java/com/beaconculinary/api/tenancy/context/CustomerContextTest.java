package com.beaconculinary.api.tenancy.context;

import com.beaconculinary.api.common.logging.RequestLogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T1-2: the current customer is set, read and put back reliably, and never leaks to other threads. */
class CustomerContextTest {
    private static final CurrentCustomer A = new CurrentCustomer(1, "customera");
    private static final CurrentCustomer B = new CurrentCustomer(2, "customerb");

    @AfterEach
    void clean() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        MDC.clear();
        assertThat(CustomerContext.current()).as("nothing left behind").isEmpty();
    }

    @Test
    void noCustomer_byDefault_andRequireSaysSo() {
        assertThat(CustomerContext.current()).isEmpty();
        assertThatThrownBy(CustomerContext::require).isInstanceOf(NoCurrentCustomerException.class)
                .hasMessageContaining("No current customer");
    }

    @Test
    void runAs_setsTheCustomerAndTheLogField_thenPutsThePreviousBack_nested() {
        MDC.put(RequestLogContext.TENANT_ID, RequestLogContext.DEFAULT_TENANT);
        CustomerContext.runAs(A, () -> {
            assertThat(CustomerContext.require()).isEqualTo(A);
            assertThat(MDC.get(RequestLogContext.TENANT_ID)).isEqualTo("customera");
            var inner = CustomerContext.callAs(B, () -> CustomerContext.require().slug() + "/" + MDC.get(RequestLogContext.TENANT_ID));
            assertThat(inner).isEqualTo("customerb/customerb");
            assertThat(CustomerContext.require()).as("back to A").isEqualTo(A);
            assertThat(MDC.get(RequestLogContext.TENANT_ID)).isEqualTo("customera");
        });
        assertThat(CustomerContext.current()).isEmpty();
        assertThat(MDC.get(RequestLogContext.TENANT_ID)).as("the request's value back").isEqualTo("default");
    }

    @Test
    void whenTheWorkThrows_theCustomerIsStillCleared() {
        assertThatThrownBy(() -> CustomerContext.runAs(A, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(CustomerContext.current()).isEmpty();
        assertThat(MDC.get(RequestLogContext.TENANT_ID)).isNull();
    }

    @Test
    void openScope_closesOnce_andRestores() {
        var scope = CustomerContext.open(A);
        assertThat(CustomerContext.require()).isEqualTo(A);
        scope.close();
        scope.close();
        assertThat(CustomerContext.current()).isEmpty();
    }

    @Test
    void anotherThread_doesNotInheritTheCustomer() throws InterruptedException {
        var seen = new AtomicReference<Object>("not run");
        CustomerContext.runAs(A, () -> {
            var thread = new Thread(() -> seen.set(CustomerContext.current().orElse(null)));
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(seen.get()).isNull();
    }

    @Test
    void switchingCustomerInsideATransaction_isRefused_theSameCustomerIsAllowed() {
        CustomerContext.runAs(A, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThatThrownBy(() -> CustomerContext.runAs(B, () -> { }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Can't switch from customer customera to customerb inside a transaction");
                assertThat(CustomerContext.require()).as("unchanged after the refusal").isEqualTo(A);
                CustomerContext.runAs(A, () -> assertThat(CustomerContext.require()).isEqualTo(A));
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });
    }

    @Test
    void badCustomers_areRefused() {
        assertThatThrownBy(() -> CustomerContext.open(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrentCustomer(1, " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
