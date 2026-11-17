package com.beaconculinary.api.sessions;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.auth.JwtService;
import com.beaconculinary.api.inventory.RecipeRepository;
import com.beaconculinary.api.menu.ComponentCatalogRepository;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.menu.MealCatalogRepository;
import com.beaconculinary.api.orders.OrderAdjustmentRepository;
import com.beaconculinary.api.orders.OrderRepository;
import com.beaconculinary.api.orders.OrderStatusEventRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.PosFlowHelper;
import com.beaconculinary.api.support.TestStaffCleanup;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O2 step 5 (R7-R9, C8, C9): sessions, the sid claim, refresh and POS writes refused once a session
 * has ended, and the sign-in rules on a till. Login protection runs in monitor mode here so the new
 * refusal outcomes are recorded (monitor never blocks).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "security.login-protection.mode=monitor")
class O2SessionIntegrationTests {
    private static final String PREFIX = "O2 Sess ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderAdjustmentRepository orderAdjustmentRepository;
    @Autowired
    private OrderStatusEventRepository orderStatusEventRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;
    @Autowired
    private DailyMealOptionRepository dailyMealOptionRepository;
    @Autowired
    private DailyComponentStockRepository dailyComponentStockRepository;
    @Autowired
    private MealCatalogRepository mealCatalogRepository;
    @Autowired
    private ComponentCatalogRepository componentCatalogRepository;
    @Autowired
    private RecipeRepository recipeRepository;

    private final List<Long> staff = new ArrayList<>();
    private String adminPwd;
    private Account cashier1;
    private Account cashier2;
    private Account admin2;
    private Account chef;
    private Cookie t1;
    private Cookie t2;
    private String t1Id;

    record Account(long id, String pin) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        cashier1 = createStaff("CASHIER", false);
        cashier2 = createStaff("CASHIER", false);
        admin2 = createStaff("ADMIN", true);
        chef = createStaff("HEAD_CHEF", true);
        var till1 = registerTill();
        t1 = till1.cookie();
        t1Id = till1.id();
        t2 = registerTill().cookie();
    }

    @AfterEach
    void tearDown() {
        orderAdjustmentRepository.deleteAll();
        authorizationTokenRepository.deleteAll();
        orderStatusEventRepository.deleteAll();
        orderRepository.deleteAll();
        dailyComponentStockRepository.deleteAll();
        dailyMealOptionRepository.deleteAll();
        mealCatalogRepository.deleteAll();
        recipeRepository.deleteAll();
        componentCatalogRepository.deleteAll();
        shiftRepository.deleteAll();
        for (var id : staff) {
            jdbc.update("DELETE FROM [user_sessions] WHERE [user_id] = ?", id);
            jdbc.update("DELETE FROM [login_attempts] WHERE [user_id] = ?", id);
            jdbc.update("DELETE FROM [user_auth_state] WHERE [user_id] = ?", id);
        }
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
        clock.reset();
    }

    // ---- sid ----

    @Test
    void tokensCarryTheSessionId_andRefreshKeepsIt() throws Exception {
        var signIn = pinLogin(cashier1, null);
        var sid = claims(token(signIn)).get("sid").asText();
        assertThat(UUID.fromString(sid)).isNotNull();

        var refreshed = refresh(signIn);

        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        assertThat(claims(token(refreshed)).get("sid").asText()).isEqualTo(sid);
        assertThat(jdbc.queryForObject("SELECT [kind] FROM [user_sessions] WHERE [id] = ?", String.class, sid)).isEqualTo("POS");
    }

    @Test
    void sessionKinds_followTheSignIn() throws Exception {
        assertThat(kind(token(pinLogin(cashier1, null)))).isEqualTo("POS");
        assertThat(kind(token(pinLogin(chef, null)))).isEqualTo("STATION");
        assertThat(kind(adminPwd)).isEqualTo("BACK_OFFICE");
        assertThat(kind(token(passwordLogin(t1)))).isEqualTo("POS");
    }

    // ---- one POS session per user (R8) ----

    @Test
    void aSecondPosSignIn_replacesTheFirst_whichCanNeitherRefreshNorWrite() throws Exception {
        var first = pinLogin(cashier1, null);
        var second = pinLogin(cashier1, null);

        assertSessionEnded(refresh(first), "REPLACED");
        assertSessionEnded(openShift(token(first), null), "REPLACED");
        assertThat(refresh(second).getResponse().getStatus()).isEqualTo(200);
        assertThat(openShift(token(second), null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void aReplacedSession_canStillCollectItsSale_butCantSellAgain() throws Exception {
        var optionId = sellableOption();
        var first = pinLogin(cashier1, null);
        assertThat(openShift(token(first), null).getResponse().getStatus()).isEqualTo(201);
        var clientOrderId = UUID.randomUUID();
        assertThat(sell(token(first), clientOrderId, optionId).getResponse().getStatus()).isEqualTo(201);

        pinLogin(cashier1, null);

        var replay = sell(token(first), clientOrderId, optionId);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertSessionEnded(sell(token(first), UUID.randomUUID(), optionId), "REPLACED");
    }

    @Test
    void backOfficeAndStationSessions_areNotLimited() throws Exception {
        var office1 = passwordLogin(null);
        var office2 = passwordLogin(null);
        var station1 = pinLogin(chef, null);
        var station2 = pinLogin(chef, null);

        for (var result : List.of(office1, office2, station1, station2)) {
            assertThat(refresh(result).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void aPosSignIn_doesntEndTheSameUsersBackOfficeSession() throws Exception {
        var office = passwordLogin(null);
        pinLogin(admin2, null);

        assertThat(refresh(office).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- shift on another till (R8, C9) ----

    @Test
    void aCashierWithAShiftOnAnotherTill_cantSignInElsewhere() throws Exception {
        assertThat(openShift(token(pinLogin(cashier1, t1)), t1).getResponse().getStatus()).isEqualTo(201);

        var onT2 = pinLogin(cashier1, t2);
        assertError(onT2, 409, "SHIFT_OPEN_ON_OTHER_TILL");
        assertThat(body(onT2).get("details").get("tillCode").asText()).startsWith("T");
        assertError(pinLogin(cashier1, null), 409, "SHIFT_OPEN_ON_OTHER_TILL");
        assertThat(pinLogin(cashier1, t1).getResponse().getStatus()).isEqualTo(200);
        assertThat(latestOutcome(cashier1.id())).isEqualTo("SUCCESS");
    }

    @Test
    void theRefusal_isRecorded_andIsNotAFailure() throws Exception {
        openShift(token(pinLogin(cashier1, t1)), t1);

        pinLogin(cashier1, t2);

        assertThat(latestOutcome(cashier1.id())).isEqualTo("SHIFT_OPEN_ON_OTHER_TILL");
    }

    @Test
    void anAdmin_isExempt_whenSigningIn() throws Exception {
        assertThat(openShift(token(pinLogin(admin2, t1)), t1).getResponse().getStatus()).isEqualTo(201);

        assertThat(pinLogin(admin2, t2).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- a till with an open shift (R9, C8) ----

    @Test
    void aTillWithAShift_onlyItsOwnerOrAnAdminCanSignIn() throws Exception {
        assertThat(openShift(token(pinLogin(cashier1, t1)), t1).getResponse().getStatus()).isEqualTo(201);

        var other = pinLogin(cashier2, t1);
        assertError(other, 409, "TILL_LOCKED");
        assertThat(body(other).get("details").get("ownerName").asText()).startsWith(PREFIX);
        assertThat(body(other).get("details").get("tillCode").asText()).startsWith("T");
        assertThat(latestOutcome(cashier2.id())).isEqualTo("TILL_LOCKED");

        assertThat(pinLogin(admin2, t1).getResponse().getStatus()).isEqualTo(200);
        assertThat(pinLogin(cashier1, t1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void theUnregisteredTill_hasNoTillLockedRule() throws Exception {
        openShift(token(pinLogin(cashier1, null)), null);

        assertThat(pinLogin(cashier2, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aNewSignInOnATill_endsTheOtherPosSessionOnIt() throws Exception {
        var first = pinLogin(cashier1, t2);

        pinLogin(cashier2, t2);

        assertSessionEnded(refresh(first), "REPLACED");
    }

    @Test
    void anAdminSigningInOnALockedTill_endsTheOwnersSessionThere_butTheShiftStaysOpen() throws Exception {
        var owner = pinLogin(cashier1, t1);
        var shiftId = body(openShift(token(owner), t1)).get("id").asLong();

        pinLogin(admin2, t1);

        assertSessionEnded(refresh(owner), "REPLACED");
        assertThat(jdbc.queryForObject("SELECT [status] FROM [shifts] WHERE [id] = ?", String.class, shiftId)).isEqualTo("OPEN");
    }

    // ---- logout, revoke, tokens without a session ----

    @Test
    void logout_withTheAccessToken_endsTheSession() throws Exception {
        var signIn = pinLogin(cashier1, null);

        var logout = mockMvc.perform(post("/auth/logout").header("Authorization", "Bearer " + token(signIn))).andReturn();

        assertThat(logout.getResponse().getStatus()).isEqualTo(204);
        assertSessionEnded(refresh(signIn), "LOGOUT");
    }

    @Test
    void logout_withoutTheAccessToken_endsNothing() throws Exception {
        var signIn = pinLogin(cashier1, null);

        assertThat(mockMvc.perform(post("/auth/logout")).andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(refresh(signIn).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokingATill_endsTheSessionsOnIt() throws Exception {
        var signIn = pinLogin(cashier1, t1);

        var revoke = mockMvc.perform(post("/admin/devices/" + t1Id + "/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(200);

        assertSessionEnded(refresh(signIn), "ADMIN");
    }

    @Test
    void tokensWithoutASession_areRefused_asExpired() throws Exception {
        var user = userRepository.findById(cashier1.id()).orElseThrow();
        var access = jwtService.generateAccessToken(user).toString();
        var refreshToken = jwtService.generateRefreshToken(user).toString();

        assertSessionEnded(openShift(access, null), "EXPIRED");
        assertSessionEnded(mockMvc.perform(post("/auth/refresh").cookie(new Cookie("refreshToken", refreshToken))).andReturn(),
                "EXPIRED");
    }

    @Test
    void closingAShift_needsAnActiveSession() throws Exception {
        var first = pinLogin(cashier1, null);
        var shiftId = body(openShift(token(first), null)).get("id").asLong();
        var second = pinLogin(cashier1, null);

        var close = mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token(first))
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();

        assertSessionEnded(close, "REPLACED");
        var closed = mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + token(second))
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedCash\":500.00}")).andReturn();
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
    }

    // ---- helpers ----

    private Account createStaff(String role, boolean withPassword) throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var body = new HashMap<String, Object>(Map.of("name", PREFIX + role + " " + RANDOM.nextInt(1_000_000), "role", role, "pin", pin));
            if (withPassword) {
                body.put("email", "o2.sess." + RANDOM.nextInt(1_000_000_000) + "@example.test");
                body.put("password", "Pw" + Long.toString(Math.abs(RANDOM.nextLong()), 36) + "x9Q");
            }
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminPwd)
                    .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body))).andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                staff.add(id);
                return new Account(id, pin);
            }
        }
        throw new IllegalStateException("Could not create " + role);
    }

    record Till(String id, Cookie cookie) {
    }

    private Till registerTill() throws Exception {
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", PREFIX + RANDOM.nextInt(1_000_000),
                        "serialNumber", "O2S" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase())))).andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        return new Till(body(result).get("id").asText(), cookie(result, "deviceToken"));
    }

    private long sellableOption() throws Exception {
        var pos = new PosFlowHelper(mockMvc, clock, dailyMealOptionRepository, dailyComponentStockRepository);
        var mealId = pos.createMealCatalog("O2 Sess Meal", "50.00", pos.createComponent("O2 Sess Beef", "15.00"));
        return pos.createDailyOption(pos.periodId("lunch"), mealId, 100);
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, Cookie till) {
        return till == null ? request : request.cookie(till);
    }

    private MvcResult pinLogin(Account account, Cookie till) throws Exception {
        return mockMvc.perform(on(post("/auth/pin-login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", account.id(), "pin", account.pin())))).andReturn();
    }

    private MvcResult passwordLogin(Cookie till) throws Exception {
        return mockMvc.perform(on(post("/auth/login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321")))).andReturn();
    }

    private MvcResult refresh(MvcResult signIn) throws Exception {
        return mockMvc.perform(post("/auth/refresh").cookie(cookie(signIn, "refreshToken"))).andReturn();
    }

    private MvcResult openShift(String token, Cookie till) throws Exception {
        return mockMvc.perform(on(post("/shifts/open"), till).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal("500.00"))))).andReturn();
    }

    private MvcResult sell(String token, UUID clientOrderId, long optionId) throws Exception {
        var body = Map.of("clientOrderId", clientOrderId.toString(),
                "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00"), "amountTendered", new BigDecimal("50.00"))),
                "lines", List.of(Map.of("dailyMealOptionId", optionId, "quantity", 1, "extras", List.of())));
        return mockMvc.perform(post("/orders").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();
    }

    private String kind(String accessToken) throws Exception {
        return jdbc.queryForObject("SELECT [kind] FROM [user_sessions] WHERE [id] = ?", String.class,
                claims(accessToken).get("sid").asText());
    }

    private String latestOutcome(long userId) {
        return jdbc.queryForObject("SELECT TOP 1 [outcome] FROM [login_attempts] WHERE [user_id] = ? ORDER BY [id] DESC",
                String.class, userId);
    }

    private static Cookie cookie(MvcResult result, String name) {
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
            }
        }
        throw new AssertionError("no " + name + " cookie");
    }

    private static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in: " + result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("token").asText();
    }

    private static JsonNode claims(String jwt) throws Exception {
        return MAPPER.readTree(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: " + result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).as("code").isEqualTo(code);
    }

    private static void assertSessionEnded(MvcResult result, String reason) throws Exception {
        assertError(result, 401, "SESSION_ENDED");
        assertThat(body(result).get("details").get("reason").asText()).isEqualTo(reason);
    }
}
