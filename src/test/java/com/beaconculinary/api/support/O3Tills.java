package com.beaconculinary.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O3 tests: registered tills, cashiers with a PIN, sign-in on a till and shifts, plus the cleanup of
 * everything they create (offline rows first, for their foreign keys). One instance per test.
 */
public class O3Tills {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final String prefix;
    private final String adminToken;
    private final List<Long> staff = new ArrayList<>();

    public record Till(String id, String code, String name, Cookie cookie) {
    }

    public record Cashier(long id, String name, String pin) {
    }

    public O3Tills(MockMvc mockMvc, JdbcTemplate jdbc, String prefix, String adminToken) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.prefix = prefix;
        this.adminToken = adminToken;
    }

    public Cashier createCashier() throws Exception {
        for (int tries = 0; tries < 5; tries++) {
            var pin = String.format("%06d", RANDOM.nextInt(1_000_000));
            var name = prefix + "Cashier " + RANDOM.nextInt(1_000_000);
            var result = mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(Map.of("name", name, "role", "CASHIER", "pin", pin))))
                    .andReturn();
            if (result.getResponse().getStatus() == 201) {
                var id = body(result).get("id").asLong();
                staff.add(id);
                return new Cashier(id, name, pin);
            }
        }
        throw new IllegalStateException("Could not create a cashier");
    }

    public Till registerTill() throws Exception {
        var name = prefix + RANDOM.nextInt(1_000_000);
        var result = mockMvc.perform(post("/admin/devices").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(Map.of(
                        "name", name, "serialNumber", "O3" + Long.toString(Math.abs(RANDOM.nextLong()), 36).toUpperCase()))))
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

    public static MockHttpServletRequestBuilder on(MockHttpServletRequestBuilder request, Till till) {
        return till == null ? request : request.cookie(till.cookie());
    }

    /** A PIN sign-in on {@code till} (null: no cookie); returns the access token. */
    public String pinLogin(Cashier cashier, Till till) throws Exception {
        var result = mockMvc.perform(on(post("/auth/pin-login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashier.id(), "pin", cashier.pin())))).andReturn();
        return token(result);
    }

    /** The seeded admin's password sign-in on {@code till} (null: no cookie, a back-office session). */
    public String adminLogin(Till till) throws Exception {
        var result = mockMvc.perform(on(post("/auth/login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321"))))
                .andReturn();
        return token(result);
    }

    public long openShift(String token, Till till) throws Exception {
        var result = mockMvc.perform(on(post("/shifts/open"), till).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("openingFloat", new BigDecimal("500.00"))))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asLong();
    }

    /** Removes the offline rows, shifts, sessions, tills and cashiers these tests created. */
    public void cleanUp() {
        var devices = "SELECT [id] FROM [devices] WHERE [name] LIKE ?";
        var like = prefix + "%";
        jdbc.update("DELETE FROM [offline_grants] WHERE [device_id] IN (" + devices + ")", like);
        jdbc.update("DELETE FROM [offline_sequences] WHERE [device_id] IN (" + devices + ")", like);
        jdbc.update("DELETE FROM [offline_till_pending] WHERE [device_id] IN (" + devices + ")", like);
        jdbc.update("DELETE FROM [offline_till_status] WHERE [device_id] IN (" + devices + ")", like);
        var shifts = new ArrayList<>(jdbc.queryForList(
                "SELECT [id] FROM [shifts] WHERE [device_id] IN (" + devices + ")", Long.class, like));
        for (var id : staff) {
            shifts.addAll(jdbc.queryForList("SELECT [id] FROM [shifts] WHERE [cashier_id] = ?", Long.class, id));
        }
        for (var id : shifts.stream().distinct().toList()) {
            jdbc.update("DELETE FROM [offline_grants] WHERE [shift_id] = ?", id);
            jdbc.execute("DISABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events; "
                    + "DELETE FROM shift_cash_count_events WHERE shift_id = " + id + "; "
                    + "ENABLE TRIGGER trg_shift_cash_count_events_insert_only ON shift_cash_count_events;");
            jdbc.update("DELETE FROM [shift_signoffs] WHERE [shift_id] = ?", id);
            jdbc.update("DELETE FROM [shifts] WHERE [id] = ?", id);
        }
        for (var id : staff) {
            jdbc.update("DELETE FROM [user_sessions] WHERE [user_id] = ?", id);
        }
        jdbc.update("DELETE FROM [user_sessions] WHERE [device_id] IN (" + devices + ")", like);
        jdbc.update("DELETE c FROM [device_credentials] c JOIN [devices] d ON d.[id] = c.[device_id] WHERE d.[name] LIKE ?", like);
        jdbc.update("DELETE FROM [devices] WHERE [name] LIKE ?", like);
        TestStaffCleanup.delete(jdbc, staff);
        staff.clear();
    }

    public static String token(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("sign-in: " + result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).get("token").asText();
    }

    public static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
