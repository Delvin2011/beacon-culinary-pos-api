package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-6a: invite links end to end (docs/2026-11-21-t0-6-invites-api-contract.md): issuing, looking up
 * and accepting, the refusals, ending sessions, the staff list, and that the token, password and PIN
 * never reach the database or the logs. Login protection in monitor mode, so attempts are recorded.
 */
@SpringBootTest(properties = "security.login-protection.mode=monitor")
// MockMvc printing off: its request dump would show the bodies, and the log checks look at the app's output only.
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class InviteIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "T06a invite ";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String admin;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
        admin = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        TestStaffCleanup.delete(jdbc, created);
        created.clear();
    }

    // ---- helpers --------------------------------------------------------------------------------

    private JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions createStaff(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    /** Creates a person with setup INVITE; returns the response body. */
    private JsonNode invited(String role, String email) throws Exception {
        var body = new HashMap<String, Object>(Map.of("name", PREFIX + UUID.randomUUID(), "role", role, "setup", "INVITE"));
        if (email != null) {
            body.put("email", email);
        }
        var node = json(createStaff(body).andExpect(status().isCreated()));
        created.add(node.get("id").asLong());
        return node;
    }

    private long cashierWithPin(String pin) throws Exception {
        var node = json(createStaff(Map.of("name", PREFIX + UUID.randomUUID(), "role", "CASHIER", "pin", pin))
                .andExpect(status().isCreated()));
        created.add(node.get("id").asLong());
        return node.get("id").asLong();
    }

    private ResultActions issue(String bearer, long userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/admin/staff/" + userId + "/invite").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private String issuedToken(long userId, String purpose, String credentials) throws Exception {
        var body = new HashMap<String, Object>(Map.of("purpose", purpose));
        if (credentials != null) {
            body.put("credentials", credentials);
        }
        return json(issue(admin, userId, body).andExpect(status().isCreated())).get("token").asText();
    }

    private ResultActions lookup(String token) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("token", token);
        return mockMvc.perform(post("/auth/invites/lookup").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private ResultActions accept(String token, String password, String pin) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("token", token);
        body.put("password", password);
        body.put("pin", pin);
        return mockMvc.perform(post("/auth/invites/accept").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)));
    }

    private JsonNode staff(long id) throws Exception {
        return json(mockMvc.perform(get("/admin/staff/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()));
    }

    private int pinLoginStatus(long id, String pin) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", id, "pin", pin)))).andReturn().getResponse().getStatus();
    }

    private long inviteId(String token) {
        return jdbc.queryForObject("SELECT [id] FROM [user_invites] WHERE [token_hash] = ?", Long.class, InviteTokens.hash(token));
    }

    private List<String> outcomes(long inviteId) {
        return jdbc.queryForList("SELECT [outcome] FROM [login_attempts] WHERE [method] = 'INVITE' AND [identifier] = ? ORDER BY [id]",
                String.class, "invite:" + inviteId);
    }

    // ---- the whole flow -------------------------------------------------------------------------

    @Test
    void newCashierByInvite_setsTheirPin_thenSignsIn_andOnlyTheHashIsStored(CapturedOutput output) throws Exception {
        var node = invited("CASHIER", null);
        var id = node.get("id").asLong();
        assertThat(node.get("hasPin").asBoolean()).isFalse();
        assertThat(node.get("invite").get("status").asText()).isEqualTo("PENDING");
        var issued = node.get("issuedInvite");
        var token = issued.get("token").asText();
        assertThat(token).hasSize(43);
        assertThat(issued.get("purpose").asText()).isEqualTo("STAFF_SETUP");
        assertThat(issued.get("credentials").asText()).isEqualTo("PIN");
        assertThat(issued.get("link").isNull()).as("no app.public-base-url in the tests").isTrue();

        // The database holds the SHA-256 only.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_invites] WHERE [token_hash] = ?", Integer.class, token)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_invites] WHERE [token_hash] = ?", Integer.class,
                InviteTokens.hash(token))).isEqualTo(1);
        // The staff list never repeats the token.
        assertThat(staff(id).has("issuedInvite")).isFalse();

        lookup(token).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.roleLabel").value("Cashier"))
                .andExpect(jsonPath("$.purpose").value("STAFF_SETUP"))
                .andExpect(jsonPath("$.password").value("NONE"))
                .andExpect(jsonPath("$.pin").value("REQUIRED"))
                .andExpect(jsonPath("$.signInWith").value("PIN"))
                .andExpect(jsonPath("$.email").value(nullValue()));

        accept(token, null, "246813").andExpect(status().isOk())
                .andExpect(jsonPath("$.signInWith").value("PIN"));
        assertThat(pinLoginStatus(id, "246813")).isEqualTo(200);

        accept(token, null, "135792").andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITE_USED"));
        lookup(token).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITE_USED"));
        var after = staff(id);
        assertThat(after.get("hasPin").asBoolean()).isTrue();
        assertThat(after.get("invite").get("status").asText()).isEqualTo("USED");
        assertThat(after.get("invite").get("usedAt").isNull()).isFalse();

        assertThat(outcomes(inviteId(token))).containsExactly("SUCCESS", "SUCCESS", "VALIDATION", "VALIDATION");
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(InviteTokens.hash(token)).doesNotContain("246813");
    }

    @Test
    void aResetEndsEverySessionOfThePerson_andTheOldPinStopsWorking(CapturedOutput output) throws Exception {
        var id = cashierWithPin("112233");
        assertThat(pinLoginStatus(id, "112233")).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_sessions] WHERE [user_id] = ? AND [ended_at] IS NULL",
                Integer.class, id)).isPositive();

        var token = issuedToken(id, "RESET", null);
        lookup(token).andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("RESET"))
                .andExpect(jsonPath("$.pin").value("REQUIRED"));
        accept(token, null, "445566").andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [user_sessions] WHERE [user_id] = ? AND [ended_at] IS NULL",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT [end_reason] FROM [user_sessions] WHERE [user_id] = ?", String.class, id))
                .containsExactly("CREDENTIALS_CHANGED");
        assertThat(pinLoginStatus(id, "112233")).isEqualTo(401);
        assertThat(pinLoginStatus(id, "445566")).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain(token).doesNotContain("445566");
    }

    @Test
    void headChefSetsAPassword_thePinIsOptional() throws Exception {
        var email = "t06a-chef-" + UUID.randomUUID() + "@example.test";
        var node = invited("HEAD_CHEF", email);
        var token = node.get("issuedInvite").get("token").asText();
        assertThat(node.get("issuedInvite").get("credentials").asText()).isEqualTo("BOTH");

        lookup(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value("REQUIRED"))
                .andExpect(jsonPath("$.pin").value("OPTIONAL"))
                .andExpect(jsonPath("$.signInWith").value("PASSWORD"))
                .andExpect(jsonPath("$.email").value(email));
        accept(token, null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.details.fields.password").exists());
        accept(token, "Chef-pass-1", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.signInWith").value("PASSWORD"))
                .andExpect(jsonPath("$.email").value(email));
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", "Chef-pass-1"))))
                .andExpect(status().isOk());
    }

    // ---- refusals -------------------------------------------------------------------------------

    @Test
    void unknownExpiredRevokedAndReplacedLinks() throws Exception {
        lookup(InviteTokens.newToken()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_INVALID"));
        lookup("not-a-token").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_INVALID"));
        mockMvc.perform(post("/auth/invites/accept")).andExpect(status().isNotFound());

        var id = invited("CASHIER", null).get("id").asLong();
        var first = jdbc.queryForObject("SELECT TOP 1 [id] FROM [user_invites] WHERE [user_id] = ?", Long.class, id);
        var second = issuedToken(id, "STAFF_SETUP", null);
        // The first link was replaced. (Its token was only in the create response; check the row.)
        assertThat(jdbc.queryForObject("SELECT [revoke_reason] FROM [user_invites] WHERE [id] = ?", String.class, first))
                .isEqualTo("REPLACED");

        // Both rows move back in time together, so the second stays the latest.
        jdbc.update("UPDATE [user_invites] SET [created_at] = DATEADD(HOUR, -100, [created_at]), "
                + "[revoked_at] = DATEADD(HOUR, -100, [revoked_at]) WHERE [user_id] = ?", id);
        jdbc.update("UPDATE [user_invites] SET [expires_at] = DATEADD(HOUR, -1, SYSUTCDATETIME()) WHERE [id] = ?", inviteId(second));
        lookup(second).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        accept(second, null, "246813").andExpect(status().isGone()).andExpect(jsonPath("$.code").value("INVITE_EXPIRED"));
        assertThat(staff(id).get("invite").get("status").asText()).isEqualTo("EXPIRED");

        var third = issuedToken(id, "STAFF_SETUP", null);
        mockMvc.perform(post("/admin/staff/" + id + "/invite/revoke").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invite.status").value("REVOKED"));
        lookup(third).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_INVALID"));
        // Revoking with nothing open changes nothing.
        mockMvc.perform(post("/admin/staff/" + id + "/invite/revoke").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(outcomes(inviteId(second))).containsOnly("VALIDATION");
        assertThat(outcomes(inviteId(third))).containsOnly("VALIDATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [login_attempts] WHERE [method] = 'INVITE' "
                + "AND [identifier] IS NULL AND [outcome] = 'UNKNOWN_USER' AND [attempted_at] > DATEADD(MINUTE, -5, SYSUTCDATETIME())",
                Integer.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void badCredentialsKeepTheLinkUsable_andAdminPinsStayUnique() throws Exception {
        var cashier = invited("CASHIER", null).get("issuedInvite").get("token").asText();
        accept(cashier, null, "12ab").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_CREDENTIALS_INVALID"))
                .andExpect(jsonPath("$.details.fields.pin").value("PIN must be exactly 6 digits."));
        accept(cashier, "a-password", "246813").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.password").value("This link doesn't set a password."));
        accept(cashier, null, "246813").andExpect(status().isOk());

        var email = "t06a-admin-" + UUID.randomUUID() + "@example.test";
        var adminToken = invited("ADMIN", email).get("issuedInvite").get("token").asText();
        lookup(adminToken).andExpect(jsonPath("$.password").value("REQUIRED")).andExpect(jsonPath("$.pin").value("REQUIRED"));
        // 654321 is the seeded admin's PIN: manager approval matches PINs across admins.
        accept(adminToken, "Admin-pass-1", "654321").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_IN_USE"));
        accept(adminToken, "Admin-pass-1", "908172").andExpect(status().isOk());
    }

    @Test
    void aDeactivatedPersonsLinkIsRevoked_andAcceptingNeverReactivates() throws Exception {
        var node = invited("CASHIER", null);
        var id = node.get("id").asLong();
        var token = node.get("issuedInvite").get("token").asText();
        mockMvc.perform(put("/admin/staff/" + id).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("name", node.get("name").asText(), "role", "CASHIER", "active", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invite.status").value("REVOKED"));
        accept(token, null, "246813").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_INVALID"));
        assertThat(staff(id).get("active").asBoolean()).isFalse();
        issue(admin, id, Map.of("purpose", "STAFF_SETUP")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_USER_INACTIVE"));
    }

    @Test
    void someoneWaitingForTheirLinkCanBeEditedWithoutTheirCredentials() throws Exception {
        var node = invited("CASHIER", null);
        mockMvc.perform(put("/admin/staff/" + node.get("id").asLong()).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("name", PREFIX + "renamed " + UUID.randomUUID(), "role", "CASHIER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invite.status").value("PENDING"));
    }

    @Test
    void staffInviteRefusals() throws Exception {
        var cashier = cashierWithPin("223344");
        issue(admin, cashier, Map.of("purpose", "FIRST_ADMIN")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITE_REQUEST_INVALID"))
                .andExpect(jsonPath("$.details.fields.purpose").exists());
        issue(admin, cashier, Map.of("purpose", "RESET", "credentials", "PASSWORD")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.credentials").value("Cashier accounts don't use a password."));
        issue(admin, AuthTestHelper.adminId(mockMvc), Map.of("purpose", "RESET")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_SELF"));
        issue(admin, AuthTestHelper.cashierAId(mockMvc), Map.of("purpose", "RESET")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITE_DEMO_ACCOUNT"));
        issue(admin, 999_999_999L, Map.of("purpose", "RESET")).andExpect(status().isNotFound());
        issue(AuthTestHelper.loginAsStockAdmin(mockMvc), cashier, Map.of("purpose", "RESET")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("name", PREFIX + "x", "role", "CASHIER", "setup", "INVITE", "pin", "123456"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.pin").exists());
    }

    @Test
    void aLinkForAnotherAdminNeedsARecentPasswordSignIn() throws Exception {
        var email = "t06a-admin-" + UUID.randomUUID() + "@example.test";
        var otherAdmin = invited("ADMIN", email).get("id").asLong();
        var pinAdmin = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.adminId(mockMvc), "654321");

        issue(pinAdmin, otherAdmin, Map.of("purpose", "RESET")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));
        // A non-admin's link doesn't need it.
        issue(pinAdmin, cashierWithPin("334455"), Map.of("purpose", "RESET")).andExpect(status().isCreated());
        issue(admin, otherAdmin, Map.of("purpose", "RESET")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.credentials").value("BOTH"));
    }

    @Test
    void theStaffListShowsEachPersonsLatestLink() throws Exception {
        var invitedId = invited("CASHIER", null).get("id").asLong();
        var plainId = cashierWithPin("556677");
        var list = json(mockMvc.perform(get("/admin/staff").header("Authorization", "Bearer " + admin)).andExpect(status().isOk()));
        var statuses = new HashMap<Long, String>();
        list.forEach(person -> statuses.put(person.get("id").asLong(), person.get("invite").get("status").asText()));
        assertThat(statuses).containsEntry(invitedId, "PENDING").containsEntry(plainId, "NONE");
        list.forEach(person -> assertThat(person.has("issuedInvite")).isFalse());
    }
}
