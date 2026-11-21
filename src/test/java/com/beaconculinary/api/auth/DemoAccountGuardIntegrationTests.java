package com.beaconculinary.api.auth;

import com.beaconculinary.api.auth.invite.InviteCredentials;
import com.beaconculinary.api.auth.invite.InvitePurpose;
import com.beaconculinary.api.auth.invite.InviteTokens;
import com.beaconculinary.api.auth.invite.UserInviteStore;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-6a demo guard switched on (as the prod profile does): the seeded demo accounts are refused by
 * every way in, recorded as DEMO_ACCOUNT (not a failure) and alerted, while real accounts work.
 */
@SpringBootTest(properties = {
        "security.demo-accounts.block-sign-in=true",
        "security.login-protection.mode=monitor"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class DemoAccountGuardIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "T06a guard ";
    private static final String DEMO_PASSWORD = "654321";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private DemoAccountGuard guard;
    @Autowired
    private UserRepository users;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserInviteStore inviteStore;
    @Autowired
    private Clock clock;

    private long realAdminId;
    private String realAdminEmail;

    @BeforeEach
    void setUp() {
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
        realAdminEmail = "t06a-guard-" + UUID.randomUUID() + "@example.test";
        realAdminId = jdbc.queryForObject("""
                INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active])
                OUTPUT INSERTED.[id] VALUES (?, ?, ?, 'ADMIN', ?, 1)""", Long.class,
                PREFIX + UUID.randomUUID(), realAdminEmail, passwordEncoder.encode("Real-admin-1"),
                passwordEncoder.encode("918273"));
    }

    @AfterEach
    void tearDown() {
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
    }

    private long demoId(String email) {
        return jdbc.queryForObject("SELECT [id] FROM [users] WHERE [email] = ? AND [is_demo] = 1", Long.class, email);
    }

    private String latestOutcome(String method, String identifier) {
        return jdbc.queryForObject("SELECT TOP 1 [outcome] FROM [login_attempts] WHERE [method] = ? AND [identifier] = ? ORDER BY [id] DESC",
                String.class, method, identifier);
    }

    private String passwordLogin(String email, String password, int expected) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theGuardIsOn_andADemoAccountIsNotAFailure() {
        assertThat(guard.blocking()).isTrue();
        assertThat(LoginOutcome.DEMO_ACCOUNT.isFailure()).isFalse();
    }

    @Test
    void passwordSignIn_refusesDemoAccountsWithTheRightPassword_realAdminsSignIn(CapturedOutput output) throws Exception {
        passwordLogin("admin@canteen.local", DEMO_PASSWORD, 401);
        assertThat(latestOutcome("PASSWORD", "admin@canteen.local")).isEqualTo("DEMO_ACCOUNT");
        assertThat(output.getAll()).contains("LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNT_REFUSED path=PASSWORD");

        passwordLogin(realAdminEmail, "Real-admin-1", 200);
    }

    @Test
    void pinSignIn_refusesDemoAccounts() throws Exception {
        var cashier = demoId("cashier@canteen.local");
        mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("cashierId", cashier, "pin", DEMO_PASSWORD))))
                .andExpect(status().isUnauthorized());
        assertThat(latestOutcome("PIN", String.valueOf(cashier))).isEqualTo("DEMO_ACCOUNT");
    }

    @Test
    void theLoginTilesLeaveDemoAccountsOut() throws Exception {
        var body = mockMvc.perform(get("/users/login-tiles").param("screen", "POS")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var demoIds = jdbc.queryForList("SELECT [id] FROM [users] WHERE [is_demo] = 1", Long.class);
        for (var tile : MAPPER.readTree(body)) {
            assertThat(demoIds).doesNotContain(tile.get("id").asLong());
        }
        var tileIds = new java.util.ArrayList<Long>();
        MAPPER.readTree(body).forEach(tile -> tileIds.add(tile.get("id").asLong()));
        assertThat(tileIds).contains(realAdminId);
    }

    @Test
    void theDemoAdminsPinApprovesNothing(CapturedOutput output) throws Exception {
        var token = MAPPER.readTree(passwordLogin(realAdminEmail, "Real-admin-1", 200)).get("token").asText();
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + DEMO_PASSWORD + "\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(latestOutcome("MANAGER_PIN", String.valueOf(realAdminId))).isEqualTo("DEMO_ACCOUNT");
        assertThat(output.getAll()).contains("type=DEMO_ACCOUNT_REFUSED path=MANAGER_PIN");
    }

    @Test
    void anExistingDemoSessionStopsAtItsNextRefresh(CapturedOutput output) throws Exception {
        var demoAdmin = users.findById(demoId("admin@canteen.local")).orElseThrow();
        var refresh = jwtService.generateRefreshToken(demoAdmin, SignIn.password(clock).withSession(UUID.randomUUID()));
        mockMvc.perform(post("/auth/refresh").cookie(new Cookie("refreshToken", refresh.toString())))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).contains("type=DEMO_ACCOUNT_REFUSED path=REFRESH");
    }

    @Test
    void anInviteForADemoAccountIsInvalid() throws Exception {
        var cashier = demoId("cashier@canteen.local");
        var token = InviteTokens.newToken();
        var now = UtcTimestamps.nowUtc(clock);
        var inviteId = inviteStore.insertByAdmin(cashier, InvitePurpose.RESET, InviteCredentials.PIN, InviteTokens.hash(token),
                realAdminId, "Real admin", now, now.plusHours(1));
        try {
            mockMvc.perform(post("/auth/invites/lookup").contentType(MediaType.APPLICATION_JSON)
                            .content(MAPPER.writeValueAsString(Map.of("token", token))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVITE_INVALID"));
            assertThat(latestOutcome("INVITE", "invite:" + inviteId)).isEqualTo("DEMO_ACCOUNT");
        } finally {
            jdbc.update("DELETE FROM [user_invites] WHERE [id] = ?", inviteId);
        }
    }

    @Test
    void startupReportsTheActiveDemoAccounts(CapturedOutput output) {
        guard.reportAtStartup();
        var active = jdbc.queryForObject("SELECT COUNT(*) FROM [users] WHERE [is_demo] = 1 AND [active] = 1", Long.class);
        if (active > 0) {
            assertThat(output.getAll()).contains("LOGIN_PROTECTION_ALERT type=DEMO_ACCOUNTS_ACTIVE count=" + active + " guard=on");
        } else {
            assertThat(output.getAll()).contains("Demo accounts: none active; demo guard on");
        }
        assertThat(output.getAll()).doesNotContain("@canteen.local");
    }
}
