package com.beaconculinary.api.devices;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** O1: the device filter skips GET /health (no token lookup, last-seen write, cookie renewal or
 * alert for a polling till) and still runs for everything else. */
class DeviceResolutionFilterHealthTest {
    private final DeviceResolutionFilter filter = new DeviceResolutionFilter(null, null, null, null, null, null);

    private static MockHttpServletRequest request(String contextPath, String path) {
        var request = new MockHttpServletRequest("GET", contextPath + path);
        request.setContextPath(contextPath);
        return request;
    }

    @Test
    void skipsHealth_withOrWithoutAContextPath() {
        assertThat(filter.shouldNotFilter(request("", "/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api", "/health"))).isTrue();
    }

    @Test
    void stillRunsForOtherPaths() {
        assertThat(filter.shouldNotFilter(request("", "/orders"))).isFalse();
        assertThat(filter.shouldNotFilter(request("", "/health/extra"))).isFalse();
        assertThat(filter.shouldNotFilter(request("", "/users/login-tiles"))).isFalse();
    }
}
