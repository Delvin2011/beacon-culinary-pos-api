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
import java.util.Arrays;
import java.util.Map;

import static com.beaconculinary.api.devices.DeviceTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/** WS1-1b 3-B: registering, listing, editing and revoking POS tills (required mode, one browser per till). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "security.device-trust.mode=required")
class DeviceRegistrationIntegrationTests {
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

    @Test
    void config_reportsTheSettings() throws Exception {
        var result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/admin/devices/config").header("Authorization", "Bearer " + client.adminToken)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("mode").asText()).isEqualTo("required");
        assertThat(body(result).get("multiBrowserEnabled").asBoolean()).isFalse();
        assertThat(body(result).get("requireSerial").asBoolean()).isTrue();
    }

    @Test
    void register_returns201_setsAnHttpOnlyCookie_andStoresOnlyTheHash() throws Exception {
        var result = client.register("  ws1b ab 12 " + RANDOM.nextInt(1_000_000), "Tag " + RANDOM.nextInt(1_000_000), null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        var header = setCookieHeader(result);
        assertThat(header).as("Set-Cookie deviceToken").isNotNull();
        assertThat(header).contains("HttpOnly").contains("Path=/").contains("Max-Age=" + Duration.ofDays(365).toSeconds());
        var cookie = deviceCookie(result);
        assertThat(DeviceTokens.isWellFormed(cookie.getValue())).isTrue();

        var device = body(result);
        assertThat(device.get("serialNumber").asText()).startsWith("WS1BAB12").doesNotContain(" ");
        assertThat(device.get("type").asText()).isEqualTo("POS");
        assertThat(device.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(device.get("thisDevice").asBoolean()).isTrue();
        assertThat(device.get("credentials")).hasSize(1);
        assertThat(device.get("credentials").get(0).get("browserLabel").asText()).isEqualTo("Chrome on Windows");
        assertThat(device.get("credentials").get(0).get("thisBrowser").asBoolean()).isTrue();
        // The token is never in a response body, and only its SHA-256 is stored.
        assertThat(result.getResponse().getContentAsString().contains(cookie.getValue())).isFalse();
        var stored = jdbc.queryForObject("SELECT [token_hash] FROM [device_credentials] WHERE [device_id] = ?",
                byte[].class, device.get("id").asText());
        assertThat(Arrays.equals(stored, DeviceTokens.hash(cookie.getValue()))).isTrue();
    }

    @Test
    void register_needsAPasswordSignInFromTheLast30Minutes() throws Exception {
        // A PIN sign-in never counts, however recent.
        var admin = client.admin();
        var till = client.newTill();
        var pinToken = token(client.pinLogin(admin.id(), admin.pin(), till.cookie()));
        assertError(client.register(pinToken, serial(), null, null, null), 403, "REAUTH_REQUIRED");

        // A password sign-in older than reauth-max-age (the access token itself is still valid).
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(31)));
        assertError(client.register(serial(), null, null, null), 403, "REAUTH_REQUIRED");
    }

    @Test
    void register_isAdminOnly() throws Exception {
        var cashier = client.cashier();
        var till = client.newTill();
        var cashierToken = token(client.pinLogin(cashier.id(), cashier.pin(), till.cookie()));
        assertThat(client.register(cashierToken, serial(), null, null, null).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void register_fromAnAlreadyRegisteredBrowser_isRefused() throws Exception {
        var till = client.newTill();
        assertError(client.register(serial(), null, null, till.cookie()), 409, "DEVICE_ALREADY_REGISTERED");
    }

    @Test
    void register_validatesTheBody() throws Exception {
        var result = client.register("   ", null, null, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("serialNumber");
    }

    @Test
    void serialInUse_isRefused_evenWrittenDifferently() throws Exception {
        var serial = serial();
        client.newTillWithSerial(serial);
        var result = client.register(" " + serial.toLowerCase().substring(0, 4) + " " + serial.toLowerCase().substring(4), null, null, null);
        assertError(result, 409, "DEVICE_SERIAL_IN_USE");
        assertThat(body(result).get("details").get("deviceId").asText()).isNotBlank();
    }

    @Test
    void assetTagInUse_isRefused() throws Exception {
        var tag = "Tag " + RANDOM.nextInt(1_000_000_000);
        assertThat(client.register(serial(), tag, null, null).getResponse().getStatus()).isEqualTo(201);
        assertError(client.register(serial(), tag, null, null), 409, "DEVICE_ASSET_TAG_IN_USE");
    }

    @Test
    void joinDeviceId_withMultiBrowserOff_isRefused() throws Exception {
        var till = client.newTill();
        assertError(client.register(serial(), null, till.deviceId(), null), 409, "DEVICE_JOIN_DISABLED");
    }

    @Test
    void revokedBrowser_freesTheSerial_andTheSameTillIsReattached() throws Exception {
        var serial = serial();
        var till = client.newTillWithSerial(serial);
        var credentialId = client.device(till.deviceId()).get("credentials").get(0).get("id").asText();
        assertThat(client.revokeCredential(till.deviceId(), credentialId).getResponse().getStatus()).isEqualTo(200);

        var again = client.register(serial, null, null, null);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(again).get("id").asText()).isEqualTo(till.deviceId());
        assertThat(body(again).get("credentials")).hasSize(2);
    }

    @Test
    void revokedTill_freesTheSerial_forANewTill() throws Exception {
        var serial = serial();
        var till = client.newTillWithSerial(serial);
        var revoked = client.revokeDevice(till.deviceId());
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(revoked).get("status").asText()).isEqualTo("REVOKED");
        assertThat(body(revoked).get("credentials").get(0).get("status").asText()).isEqualTo("REVOKED");

        var again = client.register(serial, null, null, null);
        assertThat(again.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(again).get("id").asText()).isNotEqualTo(till.deviceId());
    }

    @Test
    void list_marksThisTill_andUpdateRenames() throws Exception {
        var till = client.newTill();
        var listed = client.list(till.cookie());
        assertThat(listed.getResponse().getContentAsString().contains(till.cookie().getValue())).isFalse();
        var mine = body(listed).findValues("id").stream().anyMatch(id -> id.asText().equals(till.deviceId()));
        assertThat(mine).isTrue();
        assertThat(client.device(till.deviceId()).get("thisDevice").asBoolean()).isFalse();

        var newSerial = serial();
        var updated = client.update(till.deviceId(), Map.of("name", PREFIX + "Front till", "serialNumber", newSerial));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(updated).get("name").asText()).isEqualTo(PREFIX + "Front till");
        assertThat(body(updated).get("serialNumber").asText()).isEqualTo(newSerial);

        // A full replace: the serial is required, as on registration.
        assertThat(client.update(till.deviceId(), Map.of("name", PREFIX + "x")).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void unknownOrMalformedIds_are404() throws Exception {
        assertThat(client.revokeDevice("not-a-uuid").getResponse().getStatus()).isEqualTo(404);
        assertThat(client.revokeDevice(java.util.UUID.randomUUID().toString()).getResponse().getStatus()).isEqualTo(404);
    }
}
