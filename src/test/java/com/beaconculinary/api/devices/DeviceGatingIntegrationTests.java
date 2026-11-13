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
 * WS1-1b 3-D, required mode: only registered POS tills get the POS staff list, cashier and admin
 * PIN sign-in, and manager-PIN approval. Kitchen and bar screens and password sign-in stay open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.device-trust.mode=required",
        "security.login-protection.mode=monitor"
})
class DeviceGatingIntegrationTests {
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

    @Test
    void posStaffList_needsARegisteredTill() throws Exception {
        assertError(client.cashiers(null), 403, "DEVICE_NOT_REGISTERED");
        assertError(client.tiles("POS", null), 403, "DEVICE_NOT_REGISTERED");

        var till = client.newTill();
        assertThat(client.cashiers(till.cookie()).getResponse().getStatus()).isEqualTo(200);
        assertThat(client.tiles("POS", till.cookie()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void kitchenAndBarTiles_stayOpen_withoutAdmins() throws Exception {
        var admin = client.admin();
        for (var screen : new String[]{"KITCHEN", "BAR"}) {
            var result = client.tiles(screen, null);
            assertThat(result.getResponse().getStatus()).as(screen).isEqualTo(200);
            assertThat(body(result).findValuesAsText("id")).as(screen).doesNotContain(String.valueOf(admin.id()));
        }
        // On a registered till the POS list still shows admins.
        var till = client.newTill();
        assertThat(body(client.tiles("POS", till.cookie())).findValuesAsText("id")).contains(String.valueOf(admin.id()));
    }

    @Test
    void cashierPinLogin_needsARegisteredTill_andIsRecorded() throws Exception {
        var cashier = client.cashier();
        assertError(client.pinLogin(cashier.id(), cashier.pin(), null), 403, "DEVICE_NOT_REGISTERED");
        var outcome = jdbc.queryForObject("SELECT TOP 1 [outcome] FROM [login_attempts] WHERE [method] = 'PIN' AND [identifier] = ?",
                String.class, String.valueOf(cashier.id()));
        assertThat(outcome).isEqualTo("DEVICE_NOT_REGISTERED");

        var till = client.newTill();
        var token = token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));
        var claims = claims(token);
        assertThat(claims.get("amr").asText()).isEqualTo("pin");
        assertThat(claims.get("dev").asText()).isEqualTo(till.deviceId());
        assertThat(claims.get("auth_time").asLong()).isPositive();
    }

    @Test
    void adminPinLogin_andAnUnknownId_areGatedAlike() throws Exception {
        var admin = client.admin();
        var refused = client.pinLogin(admin.id(), admin.pin(), null);
        var unknown = client.pinLogin(999_999_999L, randomPin(), null);
        assertError(refused, 403, "DEVICE_NOT_REGISTERED");
        assertError(unknown, 403, "DEVICE_NOT_REGISTERED");
        assertThat(refused.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    void kitchenRoles_signInByPinWithoutATill() throws Exception {
        var chef = client.headChef();
        var token = token(client.pinLogin(chef.id(), chef.pin(), null));
        assertThat(claims(token).has("dev")).isFalse();
        assertThat(AuthTestHelper.loginAsKitchen(mockMvc)).isNotBlank();
    }

    @Test
    void passwordSignIn_isUnaffected_andMarkedPwd() throws Exception {
        var claims = claims(client.adminToken);
        assertThat(claims.get("amr").asText()).isEqualTo("pwd");
        assertThat(claims.has("dev")).isFalse();
    }

    @Test
    void managerPinApproval_needsARegisteredTill_andTheRefusalIsRecorded() throws Exception {
        var admin = client.admin();
        var cashier = client.cashier();
        var till = client.newTill();
        var cashierToken = token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));

        assertError(client.authorize(cashierToken, admin.pin(), null), 403, "DEVICE_NOT_REGISTERED");
        var outcome = jdbc.queryForObject("SELECT TOP 1 [outcome] FROM [login_attempts] WHERE [method] = 'MANAGER_PIN' AND [identifier] = ?",
                String.class, String.valueOf(cashier.id()));
        assertThat(outcome).isEqualTo("DEVICE_NOT_REGISTERED");

        assertThat(client.authorize(cashierToken, admin.pin(), till.cookie()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokedTill_isRefused() throws Exception {
        var cashier = client.cashier();
        var till = client.newTill();
        assertThat(client.revokeDevice(till.deviceId()).getResponse().getStatus()).isEqualTo(200);
        assertError(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()), 403, "DEVICE_NOT_REGISTERED");
        assertError(client.cashiers(till.cookie()), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void forgedOrMalformedCookies_areTreatedAsUnregistered() throws Exception {
        var forged = new jakarta.servlet.http.Cookie(DeviceCookies.NAME, DeviceTokens.newToken());
        var malformed = new jakarta.servlet.http.Cookie(DeviceCookies.NAME, "not-a-token");
        assertError(client.cashiers(forged), 403, "DEVICE_NOT_REGISTERED");
        assertError(client.cashiers(malformed), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void idleBrowser_expires_andIsShownAsExpired() throws Exception {
        var till = client.newTill();
        clock.setInstant(clock.instant().plus(Duration.ofDays(91)));
        assertError(client.cashiers(till.cookie()), 403, "DEVICE_NOT_REGISTERED");
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        assertThat(client.device(till.deviceId()).get("credentials").get(0).get("status").asText()).isEqualTo("EXPIRED");
    }
}
