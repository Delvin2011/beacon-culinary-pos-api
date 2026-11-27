package com.beaconculinary.api.tenancy.crosscustomer;

import com.beaconculinary.api.devices.DeviceAlerts;
import com.beaconculinary.api.devices.DeviceTrustMode;
import com.beaconculinary.api.devices.DeviceTrustProperties;
import com.beaconculinary.api.devices.DeviceUnboundRequestFilter;
import com.beaconculinary.api.support.tenancy.MultiCustomerIntegrationTest;
import com.beaconculinary.api.tenancy.context.CurrentCustomer;
import com.beaconculinary.api.tenancy.context.CustomerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-10: the devices' in-memory log state is kept per customer, so one customer's activity never suppresses or counts
 * towards another's log lines. Both customers have the same ids (cashier 7), as their databases do from the same
 * baseline. Plain unit tests, as alpha and then bravo (CustomerContext, as the customer filter sets it in multi mode).
 */
@Tag(MultiCustomerIntegrationTest.TAG)
@ExtendWith(OutputCaptureExtension.class)
class CrossCustomerDeviceLogLinesTest {
    private static final CurrentCustomer ALPHA = new CurrentCustomer(1, "alpha");
    private static final CurrentCustomer BRAVO = new CurrentCustomer(2, "bravo");
    private static final long CASHIER = 7;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** L1: an unregistered till's request is logged once per user per 5 minutes: per user AT EACH CUSTOMER. */
    @Test
    void anUnboundTillRequest_isLoggedAtEachCustomer_evenForTheSameUserId(CapturedOutput output) {
        var properties = new DeviceTrustProperties();
        properties.setMode(DeviceTrustMode.OPTIONAL);
        var filter = new DeviceUnboundRequestFilter(properties);

        for (var customer : List.of(ALPHA, BRAVO, ALPHA)) {
            CustomerContext.runAs(customer, () -> cashierPostsAnOrder(filter));
        }

        assertThat(output.getOut().lines().filter(line -> line.contains("DEVICE_UNBOUND_TILL_REQUEST user=" + CASHIER)))
                .as("alpha's line (once in 5 minutes) and bravo's own").hasSize(2);
    }

    /**
     * L2: repeated refusals and invalid till tokens from one address raise an alert at the threshold (5 in 10 minutes):
     * counted AT EACH CUSTOMER, so an alert logged for bravo counts only bravo's events.
     */
    @Test
    void repeatedDeviceEvents_fromOneAddress_areCountedAtEachCustomer(CapturedOutput output) {
        var alerts = new DeviceAlerts();
        var ip = "203.0.113.9";
        CustomerContext.runAs(ALPHA, () -> repeat(3, () -> { alerts.refusal(ip); alerts.invalidToken(ip); }));
        CustomerContext.runAs(BRAVO, () -> repeat(2, () -> { alerts.refusal(ip); alerts.invalidToken(ip); }));
        assertThat(alertLines(output)).as("3 at alpha and 2 at bravo: neither reached 5").isEmpty();

        CustomerContext.runAs(BRAVO, () -> repeat(3, () -> { alerts.refusal(ip); alerts.invalidToken(ip); }));
        assertThat(alertLines(output)).as("bravo's own 5th of each").hasSize(2)
                .allMatch(line -> line.contains("ip=" + ip + " count=5"));
    }

    private static List<String> alertLines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("LOGIN_PROTECTION_ALERT type=DEVICE_")).toList();
    }

    private static void repeat(int times, Runnable action) {
        for (int i = 0; i < times; i++) {
            action.run();
        }
    }

    private static void cashierPostsAnOrder(DeviceUnboundRequestFilter filter) {
        var request = new MockHttpServletRequest("POST", "/orders");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                CASHIER, null, List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))));
        try {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            SecurityContextHolder.clearContext();
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
