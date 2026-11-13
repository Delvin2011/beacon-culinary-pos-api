package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.JsonNode;
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

import static com.beaconculinary.api.auth.protection.LoginProtectionTestClient.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS1-1: the staff record lists short-term blocks ({@code blocks.PIN}, {@code blocks.MANAGER_PIN},
 * {@code blocks.CHANGE_PASSWORD}) so admins can see who needs an unlock. Enforce mode, test clock;
 * same settings as the other enforce tests, so the Spring context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = {
        "security.login-protection.mode=enforce",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 2000"
})
class LoginStaffBlocksIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private LoginProtectionTestClient client;
    private static boolean swept;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        client = new LoginProtectionTestClient(mockMvc, jdbc, "WS1 Blk");
        client.captureProtectionErrors();
        if (!swept) {
            TestStaffCleanup.deleteByNamePrefix(jdbc, "WS1 ");
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
        client.assertNoProtectionErrors();
    }

    private Instant tick() {
        clock.setInstant(clock.instant().plusSeconds(1));
        return clock.instant();
    }

    private JsonNode blocks(long id) throws Exception {
        return client.staff(id).get("blocks");
    }

    private static void assertNotBlocked(JsonNode block) {
        assertThat(block.get("blocked").asBoolean()).isFalse();
        assertThat(block.get("blockedUntil").isNull()).isTrue();
    }

    /** Five wrong PINs from one address, returning the time of the first. */
    private Instant fiveWrongPins(LoginProtectionTestClient.Account cashier, String ip) throws Exception {
        Instant first = null;
        for (int i = 0; i < 5; i++) {
            var at = tick();
            first = first == null ? at : first;
            client.pinLogin(cashier.id(), wrongPin(cashier.pin()), ip);
        }
        return first;
    }

    @Test
    void pinBlock_isListed_untilTheLatestTillBlockEnds() throws Exception {
        var cashier = client.cashier();
        assertNotBlocked(blocks(cashier.id()).get("PIN"));

        var firstAtTillA = fiveWrongPins(cashier, "10.30.0.1");
        var pin = blocks(cashier.id()).get("PIN");
        assertThat(pin.get("blocked").asBoolean()).isTrue();
        assertThat(Instant.parse(pin.get("blockedUntil").asText())).isEqualTo(firstAtTillA.plusSeconds(900));

        var firstAtTillB = fiveWrongPins(cashier, "10.30.0.2");
        assertThat(Instant.parse(blocks(cashier.id()).get("PIN").get("blockedUntil").asText()))
                .isEqualTo(firstAtTillB.plusSeconds(900));

        // Account locks are separate and untouched.
        assertThat(client.staff(cashier.id()).get("locks").get("PIN").get("locked").asBoolean()).isFalse();
        assertNotBlocked(blocks(cashier.id()).get("MANAGER_PIN"));
        assertNotBlocked(blocks(cashier.id()).get("CHANGE_PASSWORD"));
    }

    @Test
    void pinBlock_clearsWithUnlock() throws Exception {
        var cashier = client.cashier();
        fiveWrongPins(cashier, "10.31.0.1");
        assertThat(blocks(cashier.id()).get("PIN").get("blocked").asBoolean()).isTrue();

        var unlocked = client.unlock(cashier.id(), "PIN");
        assertThat(unlocked.getResponse().getStatus()).isEqualTo(200);
        assertNotBlocked(body(unlocked).get("blocks").get("PIN"));
        assertNotBlocked(blocks(cashier.id()).get("PIN"));
    }

    @Test
    void pinBlock_clearsWithASuccessfulSignInElsewhere_andWhenTheWindowPasses() throws Exception {
        var cashier = client.cashier();
        fiveWrongPins(cashier, "10.32.0.1");
        tick();
        assertThat(client.pinLogin(cashier.id(), cashier.pin(), "10.32.0.2").getResponse().getStatus()).isEqualTo(200);
        assertNotBlocked(blocks(cashier.id()).get("PIN"));

        var other = client.cashier();
        fiveWrongPins(other, "10.32.0.3");
        assertThat(blocks(other.id()).get("PIN").get("blocked").asBoolean()).isTrue();
        clock.setInstant(clock.instant().plusSeconds(900));
        assertNotBlocked(blocks(other.id()).get("PIN"));
    }

    @Test
    void managerPinBlock_isListedForTheCaller() throws Exception {
        var adminPin = client.adminPin();
        var caller = client.cashier();
        var token = token(client.pinLogin(caller.id(), caller.pin(), "10.33.0.1"));
        Instant first = null;
        for (int i = 0; i < 5; i++) {
            var at = tick();
            first = first == null ? at : first;
            client.authorize(token, wrongPin(adminPin));
        }
        var managerPin = blocks(caller.id()).get("MANAGER_PIN");
        assertThat(managerPin.get("blocked").asBoolean()).isTrue();
        assertThat(Instant.parse(managerPin.get("blockedUntil").asText())).isEqualTo(first.plusSeconds(900));
        assertNotBlocked(blocks(caller.id()).get("PIN"));
    }

    @Test
    void changePasswordBlock_isListed_inTheStaffList() throws Exception {
        var user = client.passwordUser();
        tick();
        var token = token(client.passwordLogin(user.email(), user.password()));
        for (int i = 0; i < 5; i++) {
            tick();
            client.changePassword(token, user.id(), randomPassword(), randomPassword());
        }
        var list = MAPPER.readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/admin/staff").header("Authorization", "Bearer " + client.adminToken)).andReturn()
                .getResponse().getContentAsString());
        JsonNode mine = null;
        for (var node : list) {
            if (node.get("id").asLong() == user.id()) {
                mine = node;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("blocks").get("CHANGE_PASSWORD").get("blocked").asBoolean()).isTrue();
        assertNotBlocked(mine.get("blocks").get("PIN"));
    }
}
