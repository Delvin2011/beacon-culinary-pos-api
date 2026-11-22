package com.beaconculinary.api.ops;

import com.beaconculinary.api.auth.invite.InviteTokens;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.support.DailyPlanTestHelper;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T0-7 smoke test: the app trades on a database built from the baseline, with no seed of any kind. The database
 * is set up by {@link NewDatabaseCommand} (no starter) before the context starts; the app then runs on it with
 * app.flyway.include-baseline on, as in production (which also tests that setting). Through the API: the first
 * admin by {@link FirstAdminCommand}, the invite accepted,
 * a password sign-in, a meal period, a component and a set meal created and planned for today, a shift opened and a
 * sale made.
 *
 * <p>The throwaway database is passed in as TEST_DATASOURCE_URL (a system property, read by
 * TestDatabaseEnvironmentPostProcessor) and dropped afterwards. The context is marked dirty, and its own setting
 * keeps it from being shared with other test classes. Skipped when the test login can't create databases.
 */
@SpringBootTest(properties = "app.flyway.include-baseline=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BaselineSmokeIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN = Pattern.compile("#t=([A-Za-z0-9_-]{43})");
    private static ThrowawayDatabase database;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DailyMealOptionRepository dailyMealOptions;
    @Autowired
    private Clock clock;

    @BeforeAll
    static void newCustomerDatabase() throws Exception {
        var env = Dotenv.configure().ignoreIfMissing().load();
        var configured = env.get("TEST_DATASOURCE_URL", "");
        var url = configured.isBlank()
                ? "jdbc:sqlserver://localhost:1433;databaseName=BeaconculinaryTest;encrypt=false;trustServerCertificate=true"
                : configured;
        var username = env.get("SPRING_DATASOURCE_USERNAME");
        var password = env.get("SPRING_DATASOURCE_PASSWORD");
        Assumptions.assumeTrue(username != null && ThrowawayDatabase.canCreate(url, username, password),
                "the test login can't create databases");
        database = ThrowawayDatabase.create(url, username, password, "Smoke");
        var command = new NewDatabaseCommand(database.url(), username, password, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), Clock.systemUTC());
        assertThat(command.run(new String[0])).isEqualTo(NewDatabaseCommand.OK);
        System.setProperty("TEST_DATASOURCE_URL", database.url());
    }

    @AfterAll
    static void dropIt() throws Exception {
        System.clearProperty("TEST_DATASOURCE_URL");
        if (database != null) {
            database.close();
        }
    }

    private JsonNode json(ResultActions result) throws Exception {
        return MAPPER.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions postAs(String token, String path, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body));
        return mockMvc.perform(token == null ? request : request.header("Authorization", "Bearer " + token));
    }

    @Test
    void firstAdminPlansAMealOpensAShiftAndMakesASale() throws Exception {
        // The app is on the baseline-built database: no users, no menu.
        try (var c = dataSource.getConnection(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT DB_NAME(), (SELECT COUNT(*) FROM [users]), (SELECT COUNT(*) FROM [meal_catalog])")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(database.name());
            assertThat(rs.getInt(2)).isZero();
            assertThat(rs.getInt(3)).isZero();
        }

        // First admin (T0-6a), through the operator command.
        var out = new ByteArrayOutputStream();
        var firstAdmin = new FirstAdminCommand(dataSource::getConnection, Map.of(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()), Clock.systemUTC());
        assertThat(firstAdmin.run(new String[]{"--name", "T07 Smoke Admin", "--email", "t07-smoke@example.test",
                "--base-url", "https://pos.example.test"})).isEqualTo(FirstAdminCommand.OK);
        var matcher = TOKEN.matcher(out.toString(StandardCharsets.UTF_8));
        assertThat(matcher.find()).isTrue();
        var token = matcher.group(1);
        assertThat(InviteTokens.wellFormed(token)).isTrue();
        postAs(null, "/auth/invites/accept", Map.of("token", token, "password", "Smoke-admin-1", "pin", "735142"))
                .andExpect(status().isOk());
        var admin = json(postAs(null, "/auth/login", Map.of("email", "t07-smoke@example.test", "password", "Smoke-admin-1"))
                .andExpect(status().isOk())).get("token").asText();

        // Plan a set meal for today, under a period the admin creates (the baseline has only All Day).
        var period = json(postAs(admin, "/admin/meal-periods",
                Map.of("name", "Smoke service", "startTime", "00:00", "endTime", "23:59")).andExpect(status().isCreated()));
        var categories = json(mockMvc.perform(get("/admin/catalog/categories").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()));
        long setMeals = 0;
        for (var group : categories) {
            if ("SET_MEALS".equals(group.path("group").asText()) || "Set meals".equals(group.get("name").asText())) {
                setMeals = group.get("id").asLong();
            }
        }
        assertThat(setMeals).isPositive();
        var component = json(postAs(admin, "/admin/component-catalog", Map.of("name", "Smoke rice", "extraPrice", 5))
                .andExpect(status().isCreated())).get("id").asLong();
        var meal = json(postAs(admin, "/admin/meal-catalog", Map.of("name", "Smoke curry", "categoryId", setMeals,
                "description", "smoke", "price", 45, "componentIds", new long[]{component}))
                .andExpect(status().isCreated())).get("id").asLong();
        var option = json(postAs(admin, "/admin/daily-options", Map.of("mealPeriodId", period.get("id").asLong(),
                "optionDate", LocalDate.now(clock).toString(), "mealCatalogId", meal, "plannedPortions", 10))
                .andExpect(status().isCreated())).get("id").asLong();
        DailyPlanTestHelper.markReady(dailyMealOptions, option);

        // Open a shift and sell it.
        postAs(admin, "/shifts/open", Map.of("openingFloat", 100)).andExpect(status().isCreated());
        postAs(admin, "/orders", Map.of(
                "lines", new Object[]{Map.of("dailyMealOptionId", option, "quantity", 1, "extras", new Object[0])},
                "payments", new Object[]{Map.of("method", "CASH", "amount", 45, "amountTendered", 50)}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
    }
}
