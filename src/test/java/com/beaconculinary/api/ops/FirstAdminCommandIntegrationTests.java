package com.beaconculinary.api.ops;

import com.beaconculinary.api.auth.invite.InviteTokens;
import com.beaconculinary.api.support.TestStaffCleanup;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-6a: the operator's first-admin command, run in-process against the test database with its
 * terminal output captured: it creates the admin and the link, refuses when a set-up admin exists
 * (unless forced, which is recorded), replaces an unfinished admin's link, and never writes the
 * token to the app's logs.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class FirstAdminCommandIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PREFIX = "T06a first admin ";
    private static final Pattern LINK = Pattern.compile("https://pos\\.example\\.test/invite#t=([A-Za-z0-9_-]{43})");

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    /** Active, set-up, non-demo admins switched off for a test, and switched back on after. */
    private List<Long> parked = List.of();

    @BeforeEach
    void setUp() {
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
    }

    @AfterEach
    void tearDown() {
        TestStaffCleanup.deleteByNamePrefix(jdbc, PREFIX);
        parked.forEach(id -> jdbc.update("UPDATE [users] SET [active] = 1 WHERE [id] = ?", id));
        parked = List.of();
    }

    /** The command sees no set-up admin (the seeded demo admin never counts). */
    private void parkRealAdmins() {
        parked = jdbc.queryForList("SELECT [id] FROM [users] WHERE [role] = 'ADMIN' AND [active] = 1 AND [is_demo] = 0 "
                + "AND [password] IS NOT NULL", Long.class);
        parked.forEach(id -> jdbc.update("UPDATE [users] SET [active] = 0 WHERE [id] = ?", id));
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return new FirstAdminCommand(dataSource::getConnection, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.systemUTC()).run(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String linkToken() {
        var matcher = LINK.matcher(stdout());
        assertThat(matcher.find()).as(stdout()).isTrue();
        return matcher.group(1);
    }

    private static String email() {
        return "t06a-first-" + UUID.randomUUID() + "@example.test";
    }

    private String name() {
        return PREFIX + UUID.randomUUID();
    }

    private ResultActions lookup(String token) throws Exception {
        return mockMvc.perform(post("/auth/invites/lookup").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("token", token))));
    }

    @Test
    void createsTheFirstAdminAndPrintsTheLinkOnlyToTheTerminal(CapturedOutput logs) throws Exception {
        parkRealAdmins();
        var email = email();
        assertThat(run("--name", name(), "--email", email.toUpperCase(), "--base-url", "https://pos.example.test/"))
                .as(stderr()).isEqualTo(FirstAdminCommand.OK);
        var token = linkToken();

        var user = jdbc.queryForMap("SELECT [id], [role], [active], [is_demo], [password], [pin_hash] FROM [users] WHERE [email] = ?", email);
        assertThat(user.get("role")).isEqualTo("ADMIN");
        assertThat(user.get("active")).isEqualTo(true);
        assertThat(user.get("is_demo")).isEqualTo(false);
        assertThat(user.get("password")).isNull();
        assertThat(user.get("pin_hash")).isNull();
        var invite = jdbc.queryForMap("SELECT * FROM [user_invites] WHERE [user_id] = ?", user.get("id"));
        assertThat(invite.get("purpose")).isEqualTo("FIRST_ADMIN");
        assertThat(invite.get("credentials")).isEqualTo("BOTH");
        assertThat(invite.get("created_via")).isEqualTo("OPERATOR");
        assertThat(invite.get("created_by")).isNull();
        assertThat(invite.get("operator_forced")).isEqualTo(false);
        assertThat(invite.get("token_hash")).isEqualTo(InviteTokens.hash(token));
        var created = ((java.sql.Timestamp) invite.get("created_at")).toLocalDateTime();
        var expires = ((java.sql.Timestamp) invite.get("expires_at")).toLocalDateTime();
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofHours(72));
        assertThat(Duration.between(created, LocalDateTime.now(Clock.systemUTC())).abs()).isLessThan(Duration.ofMinutes(2));

        lookup(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("FIRST_ADMIN"))
                .andExpect(jsonPath("$.password").value("REQUIRED"))
                .andExpect(jsonPath("$.pin").value("REQUIRED"))
                .andExpect(jsonPath("$.email").value(email));
        mockMvc.perform(post("/auth/invites/accept").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("token", token, "password", "First-admin-1", "pin", "807162"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", "First-admin-1"))))
                .andExpect(status().isOk());

        assertThat(logs.getAll()).doesNotContain(token);
        assertThat(stderr()).isEmpty();
    }

    @Test
    void runningAgainForAnUnfinishedAdminReplacesTheirLink() throws Exception {
        parkRealAdmins();
        var email = email();
        var name = name();
        assertThat(run("--name", name, "--email", email, "--base-url", "https://pos.example.test")).isEqualTo(FirstAdminCommand.OK);
        var first = linkToken();
        assertThat(run("--name", name, "--email", email, "--base-url", "https://pos.example.test")).isEqualTo(FirstAdminCommand.OK);
        var second = linkToken();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [users] WHERE [email] = ?", Integer.class, email)).isEqualTo(1);
        lookup(first).andExpect(status().isNotFound());
        lookup(second).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT [revoke_reason] FROM [user_invites] WHERE [token_hash] = ?", String.class,
                InviteTokens.hash(first))).isEqualTo("REPLACED");
    }

    @Test
    void refusesWhenAnAdminIsAlreadySetUp_unlessForcedWithAReason() {
        parkRealAdmins();
        jdbc.update("INSERT INTO [users] ([name], [email], [password], [role], [pin_hash], [active]) VALUES (?, ?, ?, 'ADMIN', NULL, 1)",
                name(), email(), passwordEncoder.encode("Existing-1"));
        var email = email();

        assertThat(run("--name", name(), "--email", email)).isEqualTo(FirstAdminCommand.REFUSED);
        assertThat(stderr()).contains("Refused").contains("--force");
        assertThat(stdout()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [users] WHERE [email] = ?", Integer.class, email)).isZero();

        assertThat(run("--base-url", "https://pos.example.test",
                "--name", name(), "--email", email, "--force", "--reason", "Only admin locked out; ticket 42"))
                .isEqualTo(FirstAdminCommand.OK);
        linkToken();
        assertThat(stdout()).contains("(forced)");
        var invite = jdbc.queryForMap("SELECT i.[operator_forced], i.[operator_reason] FROM [user_invites] i "
                + "JOIN [users] u ON u.[id] = i.[user_id] WHERE u.[email] = ?", email);
        assertThat(invite.get("operator_forced")).isEqualTo(true);
        assertThat(invite.get("operator_reason")).isEqualTo("Only admin locked out; ticket 42");
    }

    @Test
    void withoutABaseUrlItPrintsTheTokenAndHowToBuildTheLink() {
        parkRealAdmins();
        assertThat(run("--name", name(), "--email", email())).isEqualTo(FirstAdminCommand.OK);
        assertThat(stdout()).contains("<frontend address>/invite#t=<token>");
        assertThat(stdout().lines().anyMatch(InviteTokens::wellFormed)).isTrue();
    }

    @Test
    void refusesAnEmailThatBelongsToAnotherAccount() {
        parkRealAdmins();
        assertThat(run("--name", name(), "--email", "cashier@canteen.local")).isEqualTo(FirstAdminCommand.REFUSED);
        assertThat(stderr()).contains("existing account");
        assertThat(stdout()).isEmpty();
    }

    @Test
    void usageErrors() {
        assertThat(run()).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A", "--email", "not-an-email")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A", "--email", email(), "--force")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A", "--email", email(), "--reason", "x")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A", "--email", email(), "--base-url", "pos.example.test")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name", "A", "--email", email(), "--bogus")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(run("--name")).isEqualTo(FirstAdminCommand.USAGE);
        assertThat(stderr()).contains("Usage:");
        assertThat(stdout()).isEmpty();
    }
}
