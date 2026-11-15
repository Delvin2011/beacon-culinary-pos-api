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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.beaconculinary.api.devices.DeviceTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS1-1b 3-E, enforce + required: PIN attempts record the till and browser, PIN_PAIR counts per
 * cashier per till (not per IP), and DEVICE_PIN refuses a till after too many failures across
 * all accounts. DEVICE_PIN is lowered to 4 here so the test stays short.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.device-trust.mode=required",
        "security.device-trust.device-pin.max-failures=4",
        "security.login-protection.mode=enforce",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class DeviceLoginProtectionIntegrationTests {
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
        client.clearProtectionTables();
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        client.cleanUp();
        client.clearProtectionTables();
        clock.reset();
    }

    private void tick() {
        clock.setInstant(clock.instant().plus(Duration.ofSeconds(1)));
    }

    @Test
    void pinAttempts_recordTheTillAndTheBrowser() throws Exception {
        var cashier = client.cashier();
        var till = client.newTill();
        token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));
        var credentialId = client.device(till.deviceId()).get("credentials").get(0).get("id").asText();

        var row = jdbc.queryForMap("SELECT [device_id], [device_credential_id], [outcome] FROM [login_attempts] "
                + "WHERE [method] = 'PIN' AND [identifier] = ?", String.valueOf(cashier.id()));
        assertThat(row.get("device_id")).isEqualTo(till.deviceId());
        assertThat(row.get("device_credential_id")).isEqualTo(credentialId);
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
    }

    @Test
    void pinPair_isCountedPerTill_notPerAddress() throws Exception {
        var cashier = client.cashier();
        var tillA = client.newTill();
        var tillB = client.newTill();
        // Same IP for both tills (one client): PIN_PAIR (5) is reached on till A only.
        for (int i = 0; i < 3; i++) {
            tick();
            assertThat(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), tillA.cookie()).getResponse().getStatus()).isEqualTo(401);
        }
        for (int i = 0; i < 2; i++) {
            tick();
            assertThat(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), tillB.cookie()).getResponse().getStatus()).isEqualTo(401);
        }
        tick();
        // 5 failures from one address, but 3 on A and 2 on B: neither pair is at 5, and DEVICE_PIN (4) isn't reached.
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), tillA.cookie()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void devicePin_refusesTheTill_afterFailuresAcrossAccounts_butNotAnotherTill() throws Exception {
        var till = client.newTill();
        var other = client.newTill();
        var victim = client.cashier();
        for (int i = 0; i < 4; i++) {
            var cashier = client.cashier();
            tick();
            assertThat(client.pinLogin(cashier.id(), wrongPin(cashier.pin()), till.cookie()).getResponse().getStatus()).isEqualTo(401);
        }
        tick();
        var refused = client.pinLogin(victim.id(), victim.pin(), till.cookie());
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(refused).get("code").asText()).isEqualTo("RATE_LIMITED");
        var rule = jdbc.queryForObject("SELECT TOP 1 [rule_triggered] FROM [login_attempts] WHERE [identifier] = ? ORDER BY [id] DESC",
                String.class, String.valueOf(victim.id()));
        assertThat(rule).isEqualTo("DEVICE_PIN");

        assertThat(client.pinLogin(victim.id(), victim.pin(), other.cookie()).getResponse().getStatus()).isEqualTo(200);

        // The window ends by itself.
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(16)));
        assertThat(client.pinLogin(victim.id(), victim.pin(), till.cookie()).getResponse().getStatus()).isEqualTo(200);
    }
}
