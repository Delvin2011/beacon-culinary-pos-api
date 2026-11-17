package com.beaconculinary.api.offline;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Cashier;
import com.beaconculinary.api.support.O3Tills.Till;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** O3: POST /pos/offline/grant, its refusals, replacement, revocation and nextOfflineSeq (contract 5 to 7). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O3OfflineGrantIntegrationTests {
    private static final String PREFIX = "O3 Grant ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OfflineProperties properties;
    @Autowired
    private OfflineGrantSigner signer;
    @Autowired
    private OfflineGrantStore grants;
    @Autowired
    private OfflineSequenceStore sequences;

    private O3Tills tills;
    private String adminPwd;
    private Cashier cashier;
    private Till t1;
    private Till t2;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        properties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        cashier = tills.createCashier();
        t1 = tills.registerTill();
        t2 = tills.registerTill();
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        properties.setAccountsEnabled(true);
        tills.cleanUp();
        clock.reset();
    }

    // ---- issuing ----

    @Test
    void theShiftOwner_onTheirTill_getsASignedGrant() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);

        var result = grant(token, t1);

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        var body = body(result);
        assertThat(body.get("deviceId").asText()).isEqualTo(t1.id());
        assertThat(body.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(body.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(body.get("cashierId").asLong()).isEqualTo(cashier.id());
        assertThat(body.get("cashierName").asText()).isEqualTo(cashier.name());
        var issuedAt = Instant.parse(body.get("issuedAt").asText());
        var expiresAt = Instant.parse(body.get("expiresAt").asText());
        assertThat(Duration.between(issuedAt, expiresAt)).isEqualTo(Duration.ofHours(18));
        assertThat(body.get("tradingDay").asText()).isEqualTo(LocalDate.now(clock).toString());
        assertThat(body.get("nextOfflineSeq").asInt()).isEqualTo(1);
        assertThat(body.get("maxOfflineHours").asInt()).isEqualTo(6);
        assertThat(body.get("warnOfflineHours").asInt()).isEqualTo(5);
        assertThat(body.get("accountsEnabled").asBoolean()).isTrue();
        assertThat(body.get("accountCap").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(body.get("serverTimeUtc").asText()).endsWith("Z");

        var verified = signer.verify(body.get("grant").asText()).orElseThrow();
        var claims = verified.claims();
        assertThat(claims.grantId().toString()).isEqualTo(body.get("grantId").asText());
        assertThat(claims.deviceId().toString()).isEqualTo(t1.id());
        assertThat(claims.shiftId()).isEqualTo(shiftId);
        assertThat(claims.cashierId()).isEqualTo(cashier.id());
        assertThat(claims.expiresAt()).isEqualTo(expiresAt);

        var stored = grants.find(claims.grantId()).orElseThrow();
        assertThat(stored.active()).isTrue();
        assertThat(stored.deviceId().toString()).isEqualTo(t1.id());
        assertThat(stored.credentialId()).isEqualTo(claims.credentialId());
        assertThat(stored.shiftId()).isEqualTo(shiftId);
        assertThat(stored.cashierName()).isEqualTo(cashier.name());
        assertThat(stored.sessionId()).isNotNull();
    }

    @Test
    void aTamperedGrant_failsVerification() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var signed = body(grant(token, t1)).get("grant").asText();

        var parts = signed.split("\\.");
        var tampered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);

        assertThat(signer.verify(signed)).isPresent();
        assertThat(signer.verify(tampered)).isEmpty();
    }

    @Test
    void withAccountsOff_theGrantHasNoCap() throws Exception {
        properties.setAccountsEnabled(false);
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);

        var body = body(grant(token, t1));

        assertThat(body.get("accountsEnabled").asBoolean()).isFalse();
        assertThat(body.has("accountCap")).isFalse();
    }

    // ---- refusals ----

    @Test
    void offlineDisabled_returns403() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        properties.setEnabled(false);

        assertError(grant(token, t1), 403, "OFFLINE_DISABLED");
    }

    @Test
    void withoutARegisteredTill_returns403() throws Exception {
        var token = tills.pinLogin(cashier, null);
        tills.openShift(token, null);

        assertError(grant(token, null), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void withoutSignIn_returns401() throws Exception {
        assertThat(mockMvc.perform(on(post("/pos/offline/grant"), t1)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aSessionOnAnotherTill_orABackOfficeSession_returns403() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);

        assertError(grant(token, t2), 403, "POS_SESSION_REQUIRED");
        assertError(grant(tills.adminLogin(null), t1), 403, "POS_SESSION_REQUIRED");
    }

    @Test
    void anEndedSession_returns401SessionEnded() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        mockMvc.perform(post("/pos/lock").header("Authorization", "Bearer " + token)).andReturn();

        assertError(grant(token, t1), 401, "SESSION_ENDED");
    }

    @Test
    void withNoOpenShift_returns409() throws Exception {
        assertError(grant(tills.pinLogin(cashier, t1), t1), 409, "NO_OPEN_SHIFT");
    }

    @Test
    void anAdminCoveringSomeoneElsesShift_returns403() throws Exception {
        tills.openShift(tills.pinLogin(cashier, t1), t1);

        assertError(grant(tills.adminLogin(t1), t1), 403, "NOT_SHIFT_OWNER");
    }

    @Test
    void otherRoles_areRefused() throws Exception {
        var kitchen = AuthTestHelper.loginAsKitchen(mockMvc);

        assertThat(grant(kitchen, t1).getResponse().getStatus()).isEqualTo(403);
    }

    // ---- replacement and revocation ----

    @Test
    void aNewGrant_replacesTheOldOne() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);
        var first = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var second = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var old = grants.find(first).orElseThrow();
        assertThat(old.active()).isFalse();
        assertThat(old.revokeReason()).isEqualTo(OfflineGrantStore.RevokeReason.REPLACED);
        assertThat(old.replacedBy()).isEqualTo(second);
        assertThat(grants.find(second).orElseThrow().active()).isTrue();
        assertThat(activeGrants(shiftId)).isEqualTo(1);
    }

    @Test
    void closingTheShift_revokesItsGrant() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var close = mockMvc.perform(on(post("/shifts/" + shiftId + "/close"), t1).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();

        assertThat(close.getResponse().getStatus()).as(close.getResponse().getContentAsString()).isEqualTo(200);
        assertRevoked(grantId, OfflineGrantStore.RevokeReason.SHIFT_CLOSED);
    }

    @Test
    void forceClosingTheShift_revokesItsGrant() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        var shiftId = tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var close = mockMvc.perform(post("/admin/shifts/" + shiftId + "/force-close").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Till abandoned\"}")).andReturn();

        assertThat(close.getResponse().getStatus()).as(close.getResponse().getContentAsString()).isEqualTo(200);
        assertRevoked(grantId, OfflineGrantStore.RevokeReason.SHIFT_FORCE_CLOSED);
    }

    @Test
    void revokingTheTill_revokesItsGrants_andTheTillCanNoLongerGetOne() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var revoke = mockMvc.perform(post("/admin/devices/" + t1.id() + "/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"stolen\"}")).andReturn();

        assertThat(revoke.getResponse().getStatus()).as(revoke.getResponse().getContentAsString()).isEqualTo(200);
        assertRevoked(grantId, OfflineGrantStore.RevokeReason.TILL_REVOKED);
        assertError(grant(token, t1), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void revokingTheBrowser_revokesTheGrantsIssuedToIt() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());
        var credentialId = grants.find(grantId).orElseThrow().credentialId();

        var revoke = mockMvc.perform(post("/admin/devices/" + t1.id() + "/credentials/" + credentialId + "/revoke")
                .header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"old browser\"}")).andReturn();

        assertThat(revoke.getResponse().getStatus()).as(revoke.getResponse().getContentAsString()).isEqualTo(200);
        assertRevoked(grantId, OfflineGrantStore.RevokeReason.BROWSER_REVOKED);
    }

    @Test
    void deactivatingTheCashier_revokesTheirGrants() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        var update = mockMvc.perform(put("/admin/staff/" + cashier.id()).header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", cashier.name(), "role", "CASHIER", "active", false))))
                .andReturn();

        assertThat(update.getResponse().getStatus()).as(update.getResponse().getContentAsString()).isEqualTo(200);
        assertRevoked(grantId, OfflineGrantStore.RevokeReason.CASHIER_DEACTIVATED);
    }

    @Test
    void anUnrelatedStaffEdit_leavesTheGrantActive() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var grantId = UUID.fromString(body(grant(token, t1)).get("grantId").asText());

        mockMvc.perform(put("/admin/staff/" + cashier.id()).header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("name", cashier.name() + " B", "role", "CASHIER", "active", true))))
                .andReturn();

        assertThat(grants.find(grantId).orElseThrow().active()).isTrue();
    }

    // ---- nextOfflineSeq ----

    @Test
    void nextOfflineSeq_continuesFromTheHighestNumberSeenToday() throws Exception {
        var token = tills.pinLogin(cashier, t1);
        tills.openShift(token, t1);
        var today = LocalDate.now(clock);
        var device = UUID.fromString(t1.id());
        var now = LocalDateTime.now();

        sequences.recordSeen(device, today.minusDays(1), 90, now);
        assertThat(body(grant(token, t1)).get("nextOfflineSeq").asInt()).isEqualTo(1);

        sequences.recordSeen(device, today, 41, now);
        assertThat(body(grant(token, t1)).get("nextOfflineSeq").asInt()).isEqualTo(42);

        sequences.recordSeen(device, today, 12, now);
        assertThat(body(grant(token, t1)).get("nextOfflineSeq").asInt()).isEqualTo(42);

        sequences.recordSeen(UUID.fromString(t2.id()), today, 300, now);
        assertThat(body(grant(token, t1)).get("nextOfflineSeq").asInt()).isEqualTo(42);
    }

    // ---- helpers ----

    private MvcResult grant(String token, Till till) throws Exception {
        return mockMvc.perform(on(post("/pos/offline/grant"), till).header("Authorization", "Bearer " + token)).andReturn();
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        JsonNode body = body(result);
        assertThat(body.get("code").asText()).isEqualTo(code);
    }

    private void assertRevoked(UUID grantId, OfflineGrantStore.RevokeReason reason) {
        var row = grants.find(grantId).orElseThrow();
        assertThat(row.active()).isFalse();
        assertThat(row.revokeReason()).isEqualTo(reason);
        assertThat(row.revokedAt()).isNotNull();
    }

    private int activeGrants(long shiftId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [offline_grants] WHERE [shift_id] = ? AND [revoked_at] IS NULL",
                Integer.class, shiftId);
    }
}
