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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static com.beaconculinary.api.devices.DeviceTestClient.PREFIX;
import static com.beaconculinary.api.devices.DeviceTestClient.assertError;
import static com.beaconculinary.api.devices.DeviceTestClient.body;
import static com.beaconculinary.api.devices.DeviceTestClient.serial;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * O2 step 3 (R1, R2): till codes and the active-till limit.
 *
 * <p>The suite pins the limit high (LoginProtectionTestDefaults) because tests share one database;
 * this class sets it back to 3 and starts each test from no active tills, revoking any left in the
 * test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "pos.tills.max-active=3")
class O2TillCodeIntegrationTests {
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
        // Each test counts tills from zero: retire any active till left in the test database.
        jdbc.update("""
                UPDATE [devices] SET [status] = 'REVOKED', [revoked_by] = 0, [revoked_by_name] = 'O2 test reset',
                    [revoked_at] = SYSUTCDATETIME(), [revoke_reason] = 'O2 test reset'
                WHERE [status] = 'ACTIVE'
                """);
        client.adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        client.cleanUp();
        clock.reset();
    }

    @Test
    void newTills_getT1T2T3_inOrder_andTheListShowsThem() throws Exception {
        var t1 = client.newTill();
        var t2 = client.newTill();
        var t3 = client.newTill();

        assertThat(client.device(t1.deviceId()).get("tillCode").asText()).isEqualTo("T1");
        assertThat(client.device(t2.deviceId()).get("tillCode").asText()).isEqualTo("T2");
        assertThat(client.device(t3.deviceId()).get("tillCode").asText()).isEqualTo("T3");
    }

    @Test
    void registerResponse_carriesTheTillCode() throws Exception {
        var result = client.register(serial(), null, null, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("tillCode").asText()).isEqualTo("T1");
    }

    @Test
    void aFourthNewTill_isRefused_andNothingIsCreated() throws Exception {
        client.newTill();
        client.newTill();
        client.newTill();
        var before = activeTills();

        var result = client.register(serial(), null, null, null);

        assertError(result, 409, "TILL_LIMIT_REACHED");
        var details = body(result).get("details");
        assertThat(details.get("maxActive").asInt()).isEqualTo(3);
        assertThat(details.get("activeCount").asInt()).isEqualTo(3);
        assertThat(DeviceTestClient.deviceCookie(result)).as("no cookie on a refusal").isNull();
        assertThat(activeTills()).isEqualTo(before);
    }

    @Test
    void joiningAnExistingTill_atTheLimit_isAllowed_andKeepsItsCode() throws Exception {
        client.newTill();
        var serial = serial();
        var t2 = client.newTillWithSerial(serial);
        client.newTill();
        // The till's only browser is revoked: the till stays active, and its serial can be registered again.
        var credentialId = client.device(t2.deviceId()).get("credentials").get(0).get("id").asText();
        assertThat(client.revokeCredential(t2.deviceId(), credentialId).getResponse().getStatus()).isEqualTo(200);

        var result = client.register(serial, null, null, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(result).get("id").asText()).isEqualTo(t2.deviceId());
        assertThat(body(result).get("tillCode").asText()).isEqualTo("T2");
        assertThat(activeTills()).isEqualTo(3);
    }

    @Test
    void revokingATill_freesItsCode_forTheNextNewTill() throws Exception {
        client.newTill();
        var t2 = client.newTill();
        client.newTill();
        assertThat(client.revokeDevice(t2.deviceId()).getResponse().getStatus()).isEqualTo(200);

        var t4 = client.newTill();

        assertThat(client.device(t4.deviceId()).get("tillCode").asText()).isEqualTo("T2");
        // The revoked till keeps its code in the list, for history.
        var revoked = client.device(t2.deviceId());
        assertThat(revoked.get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.get("tillCode").asText()).isEqualTo("T2");
    }

    @Test
    void editTillCode_normalisesIt_andOmittingItKeepsIt() throws Exception {
        var serial = serial();
        var till = client.newTillWithSerial(serial);

        var changed = client.update(till.deviceId(), edit(serial, "  front-1 "));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(changed).get("tillCode").asText()).isEqualTo("FRONT-1");

        var unchanged = client.update(till.deviceId(), edit(serial, null));
        assertThat(unchanged.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(unchanged).get("tillCode").asText()).isEqualTo("FRONT-1");
    }

    @Test
    void editTillCode_toAnotherActiveTillsCode_isRefused() throws Exception {
        var t1 = client.newTill();
        var serial = serial();
        var t2 = client.newTillWithSerial(serial);

        var result = client.update(t2.deviceId(), edit(serial, "t1"));

        assertError(result, 409, "TILL_CODE_IN_USE");
        assertThat(body(result).get("details").get("deviceId").asText()).isEqualTo(t1.deviceId());
        assertThat(client.device(t2.deviceId()).get("tillCode").asText()).isEqualTo("T2");
    }

    @Test
    void editTillCode_toARevokedTillsCode_isAllowed() throws Exception {
        var t1 = client.newTill();
        var serial = serial();
        var t2 = client.newTillWithSerial(serial);
        client.revokeDevice(t1.deviceId());

        var result = client.update(t2.deviceId(), edit(serial, "T1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("tillCode").asText()).isEqualTo("T1");
    }

    @Test
    void editTillCode_invalid_returnsAFieldError() throws Exception {
        var serial = serial();
        var till = client.newTillWithSerial(serial);

        for (var bad : new String[]{"T 1", "T_1", "ABCDEFGHIJK", "   "}) {
            var result = client.update(till.deviceId(), edit(serial, bad));
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(body(result).has("tillCode")).as(bad).isTrue();
        }
    }

    @Test
    void config_reportsTheTillSettings() throws Exception {
        var result = mockMvc.perform(get("/admin/devices/config").header("Authorization", "Bearer " + client.adminToken))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(result).get("maxActiveTills").asInt()).isEqualTo(3);
        assertThat(body(result).get("requireRegistered").asBoolean()).isFalse();
    }

    private Map<String, Object> edit(String serial, String tillCode) {
        var body = new HashMap<String, Object>();
        body.put("name", PREFIX + "Till edited");
        body.put("serialNumber", serial);
        if (tillCode != null) {
            body.put("tillCode", tillCode);
        }
        return body;
    }

    private int activeTills() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [devices] WHERE [status] = 'ACTIVE'", Integer.class);
    }
}
