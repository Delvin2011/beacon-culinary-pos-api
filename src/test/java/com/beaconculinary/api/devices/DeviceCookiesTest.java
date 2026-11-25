package com.beaconculinary.api.devices;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-5: the deviceToken cookie is host-only (no Domain attribute), so a browser never sends one customer's till
 * cookie to another customer's address.
 */
class DeviceCookiesTest {

    @Test
    void theDeviceCookie_hasNoDomain() {
        var response = new MockHttpServletResponse();
        new DeviceCookies(new DeviceTrustProperties(), true).write(response, "not-a-real-token");
        var header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("deviceToken=").contains("HttpOnly").contains("Secure").contains("Path=/")
                .doesNotContainIgnoringCase("Domain=");
    }
}
