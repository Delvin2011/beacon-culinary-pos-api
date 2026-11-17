package com.beaconculinary.api.tills;

import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.TestStaffCleanup;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O2: GET /admin/tills, every active till with its open shift and lock state. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O2AdminTillsIntegrationTests {
    private static final String PREFIX = "O2 Tills ";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ShiftRepository shiftRepository;

    private final List<Long> staff = new ArrayList<>();
    private String adminPwd;
    private long cashierId;
    private String cashierPin;
    private Till t1;
    private Till t2;

    record Till(String id, String code, String name, Cookie cookie) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        createCashier();
        t1 = registerTill();
        t2 = registerTill();
    }

    @AfterEach
    void tearDown() {
        shiftRepository.deleteAll();
        for (var id : staff) {
            jdbc.update("DELETE FROM [user_sessions] WHERE [user_id] = ?", id);
        }
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", PREFIX + "%");
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", PREFIX + "%");
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
        clock.reset();
    }

    @Test
    void listsEachActiveTill_withItsShift_owner_andLockState() throws Exception {
        var signIn = pinLogin(t1);
        var shiftId = openShift(token(signIn), t1);

        var onT1 = row(tills(), t1.id());
        assertThat(onT1.get("tillCode").asText()).isEqualTo(t1.code());
        assertThat(onT1.get("tillName").asText()).isEqualTo(t1.name());
        assertThat(onT1.get("registered").asBoolean()).isTrue();
        var shift = onT1.get("shift");
        assertThat(shift.get("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(shift.get("ownerId").asLong()).isEqualTo(cashierId);
        assertThat(shift.get("ownerName").asText()).startsWith(PREFIX);
        assertThat(shift.get("openedAt").asText()).isNotBlank();
        assertThat(shift.get("locked").asBoolean()).isFalse();

        assertThat(row(tills(), t2.id()).get("shift").isNull()).isTrue();

        mockMvc.perform(post("/pos/lock").header("Authorization", "Bearer " + token(signIn))).andReturn();
        assertThat(row(tills(), t1.id()).get("shift").get("locked").asBoolean()).isTrue();
    }

    @Test
    void theUnregisteredTill_isListedOnlyWhileItHasAnOpenShift() throws Exception {
        assertThat(tills()).noneMatch(node -> !node.get("registered").asBoolean());

        openShift(token(pinLogin(null)), null);

        var unregistered = new ArrayList<JsonNode>();
        tills().forEach(node -> {
            if (!node.get("registered").asBoolean()) {
                unregistered.add(node);
            }
        });
        assertThat(unregistered).hasSize(1);
        var row = unregistered.get(0);
        assertThat(row.get("deviceId").isNull()).isTrue();
        assertThat(row.get("tillCode").isNull()).isTrue();
        assertThat(row.get("tillName").asText()).isEqualTo("Unregistered till");
        assertThat(row.get("shift").get("ownerId").asLong()).isEqualTo(cashierId);
        assertThat(row.get("shift").get("locked").isNull()).isTrue();
        // Listed last.
        var all = tills();
        assertThat(all.get(all.size() - 1).get("registered").asBoolean()).isFalse();
    }

    @Test
    void revokedTills_areNotListed() throws Exception {
        mockMvc.perform(post("/admin/devices/" + t2.id() + "/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andReturn();

        assertThat(tills()).noneMatch(node -> t2.id().equals(node.get("deviceId").asText()));
    }

    @Test
    void onlyAnAdminCanList() throws Exception {
        var cashier = token(pinLogin(null));

        assertThat(mockMvc.perform(get("/admin/tills").header("Authorization", "Bearer " + cashier)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/tills")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    // ---- helpers ----

    private void createCashier() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminPwd)
                    .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                            "name", PREFIX + "Cashier " + RANDOM.nextInt(1_000_000), "role", "CASHIER", "pin", pin))))
                    .andReturn();
            if (result.getResponse().getStatus() == 201) {
                cashierId = body(result).get("id").asLong();
                cashierPin = pin;
                staff.add(cashierId);
                return;
            }
        }
        throw new IllegalStateException("Could not create a cashier");
    }

    private Till registerTill() throws Exception {
        var name = PREFIX + RANDOM.nextInt(1_000_000);
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", name, "serialNumber", "O2A" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase()))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("register till").isEqualTo(201);
        Cookie cookie = null;
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("deviceToken=")) {
                cookie = new Cookie("deviceToken", header.substring("deviceToken=".length(), header.indexOf(';')));
            }
        }
        return new Till(body(result).get("id").asText(), body(result).get("tillCode").asText(), name, cookie);
    }

    private MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, Till till) {
        return till == null ? request : request.cookie(till.cookie());
    }

    private MvcResult pinLogin(Till till) throws Exception {
        return mockMvc.perform(on(post("/auth/pin-login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashierId, "pin", cashierPin)))).andReturn();
    }

    private long openShift(String token, Till till) throws Exception {
        var result = mockMvc.perform(on(post("/shifts/open"), till).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal("500.00"))))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    private List<JsonNode> tills() throws Exception {
        var result = mockMvc.perform(get("/admin/tills").header("Authorization", "Bearer " + adminPwd)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var list = new ArrayList<JsonNode>();
        body(result).forEach(list::add);
        return list;
    }

    private static JsonNode row(List<JsonNode> tills, String deviceId) {
        return tills.stream().filter(node -> deviceId.equals(node.get("deviceId").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("till not listed"));
    }

    private static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in: " + result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("token").asText();
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
