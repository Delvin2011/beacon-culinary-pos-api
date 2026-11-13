package com.beaconculinary.api.devices;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.beaconculinary.api.devices.DeviceTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/** WS1-1b: off (the default) and optional mode refuse nothing; multi-browser tills join by serial. */
class DeviceModesIntegrationTests {

    abstract static class Base {
        @Autowired
        MockMvc mockMvc;
        @Autowired
        JdbcTemplate jdbc;
        @Autowired
        MutableClock clock;
        DeviceTestClient client;

        @BeforeEach
        void setUp() throws Exception {
            clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            client = new DeviceTestClient(mockMvc, jdbc);
            client.clearProtectionTables();
            client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        }

        @AfterEach
        void tearDown() {
            client.cleanUp();
            client.clearProtectionTables();
            clock.reset();
        }
    }

    /** Off, the shipped default: nothing is gated, no dev claim, attempts carry no till; tills can be registered ahead. */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(ClockTestConfig.class)
    @TestPropertySource(properties = "security.login-protection.mode=monitor")
    class Off extends Base {
        @Test
        void nothingIsGated_andTillsCanBeRegisteredInAdvance() throws Exception {
            var cashier = client.cashier();
            assertThat(client.cashiers(null).getResponse().getStatus()).isEqualTo(200);
            var token = token(client.pinLogin(cashier.id(), cashier.pin(), null));
            assertThat(claims(token).has("dev")).isFalse();

            var till = client.newTill();
            var withTill = token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));
            assertThat(claims(withTill).has("dev")).as("no dev claim in off mode").isFalse();
            var recorded = jdbc.queryForList("SELECT [device_id] FROM [login_attempts] WHERE [method] = 'PIN' AND [identifier] = ?",
                    String.valueOf(cashier.id()));
            assertThat(recorded).as("both sign-ins recorded").hasSize(2);
            assertThat(recorded).as("no till recorded in off mode").allMatch(row -> row.get("device_id") == null);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(ClockTestConfig.class)
    @TestPropertySource(properties = {
            "security.device-trust.mode=optional",
            "security.login-protection.mode=monitor"
    })
    class Optional extends Base {
        @Test
        void nothingIsRefused_butTheTillIsRecorded() throws Exception {
            var cashier = client.cashier();
            assertThat(client.cashiers(null).getResponse().getStatus()).isEqualTo(200);
            assertThat(client.pinLogin(cashier.id(), cashier.pin(), null).getResponse().getStatus()).isEqualTo(200);

            var till = client.newTill();
            var token = token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));
            assertThat(claims(token).get("dev").asText()).isEqualTo(till.deviceId());
            var recorded = jdbc.queryForList("SELECT [device_id] FROM [login_attempts] WHERE [identifier] = ? AND [device_id] IS NOT NULL",
                    String.class, String.valueOf(cashier.id()));
            assertThat(recorded).containsOnly(till.deviceId());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(ClockTestConfig.class)
    @TestPropertySource(properties = {
            "security.device-trust.mode=required",
            "security.device-trust.multi-browser-enabled=true"
    })
    class MultiBrowser extends Base {
        @Test
        void aSecondBrowser_mustConfirmTheJoin_andThenSharesTheTill() throws Exception {
            var serial = serial();
            var first = client.newTillWithSerial(serial);

            var unconfirmed = client.register(serial, null, null, null);
            assertError(unconfirmed, 409, "DEVICE_SERIAL_EXISTS");
            assertThat(body(unconfirmed).get("details").get("deviceId").asText()).isEqualTo(first.deviceId());

            var other = client.newTill();
            assertError(client.register(serial, null, other.deviceId(), null), 409, "DEVICE_JOIN_MISMATCH");
            assertError(client.register(serial(), null, first.deviceId(), null), 409, "DEVICE_JOIN_MISMATCH");

            var joined = client.register(serial, null, first.deviceId(), null);
            assertThat(joined.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(joined).get("id").asText()).isEqualTo(first.deviceId());
            assertThat(body(joined).get("credentials")).hasSize(2);
            assertThat(client.cashiers(deviceCookie(joined)).getResponse().getStatus()).isEqualTo(200);
            assertThat(client.cashiers(first.cookie()).getResponse().getStatus()).isEqualTo(200);
        }
    }
}
