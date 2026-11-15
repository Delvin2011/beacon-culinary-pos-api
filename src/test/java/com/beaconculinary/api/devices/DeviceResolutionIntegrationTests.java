package com.beaconculinary.api.devices;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.beaconculinary.api.devices.DeviceTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS1-1b 3-C: last seen is written at most once per last-seen-interval, the cookie is re-sent at
 * that moment (sliding expiry), and a browser used now and then never reaches idle expiry.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "security.device-trust.mode=required")
class DeviceResolutionIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private DeviceTestClient client;
    private static boolean swept;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        client = new DeviceTestClient(mockMvc, jdbc);
        if (!swept) {
            DeviceTestClient.deleteTestDevices(jdbc);
            TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
            swept = true;
        }
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        client.cleanUp();
        clock.reset();
    }

    private Timestamp lastSeen(String deviceId) {
        return jdbc.queryForObject("SELECT [last_seen_at] FROM [device_credentials] WHERE [device_id] = ?", Timestamp.class, deviceId);
    }

    @Test
    void lastSeen_isThrottled_andTheCookieIsRenewedWhenItIsWritten() throws Exception {
        var till = client.newTill();
        var registered = lastSeen(till.deviceId());

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(1)));
        var soon = client.anyRequest(till.cookie());
        assertThat(soon.getResponse().getStatus()).isEqualTo(200);
        assertThat(setCookieHeader(soon)).as("no renewal within the interval").isNull();
        assertThat(lastSeen(till.deviceId())).isEqualTo(registered);

        clock.setInstant(clock.instant().plus(Duration.ofMinutes(5)));
        var later = client.anyRequest(till.cookie());
        assertThat(later.getResponse().getStatus()).isEqualTo(200);
        var renewed = deviceCookie(later);
        assertThat(renewed).as("renewed cookie").isNotNull();
        assertThat(renewed.getValue().equals(till.cookie().getValue())).as("same token re-sent").isTrue();
        assertThat(setCookieHeader(later)).contains("Max-Age=" + Duration.ofDays(365).toSeconds());
        assertThat(lastSeen(till.deviceId())).isAfter(registered);
    }

    @Test
    void aBrowserUsedWithinTheIdleWindow_neverExpires() throws Exception {
        var till = client.newTill();
        for (int i = 0; i < 3; i++) {
            clock.setInstant(clock.instant().plus(Duration.ofDays(60)));
            assertThat(client.anyRequest(till.cookie()).getResponse().getStatus()).as("day " + 60 * (i + 1)).isEqualTo(200);
        }
    }

    @Test
    void anExpiredBrowser_freesTheSerial_andTheTillIsReattached() throws Exception {
        var serial = serial();
        var till = client.newTillWithSerial(serial);
        clock.setInstant(clock.instant().plus(Duration.ofDays(91)));
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);

        var again = client.register(serial, null, null, null);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(again).get("id").asText()).isEqualTo(till.deviceId());
        assertThat(client.anyRequest(deviceCookie(again)).getResponse().getStatus()).isEqualTo(200);
    }
}
