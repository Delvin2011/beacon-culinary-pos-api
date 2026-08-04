package com.beaconculinary.api.shifts;

import com.beaconculinary.api.support.AuthTestHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises /shifts/** against the seed cashiers from V13__seed_menu_and_users.sql and
 * V14__add_pin_auth_to_users.sql: Cashier A (id 1, email login), Admin (id 2), and
 * Cashier B (id 3, PIN-only) used specifically to test cross-cashier shift ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShiftIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShiftRepository shiftRepository;

    private String cashierAToken;
    private String cashierBToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        cashierAToken = AuthTestHelper.loginAsCashier(mockMvc);
        cashierBToken = AuthTestHelper.loginWithPin(mockMvc, AuthTestHelper.CASHIER_B_ID, "654321");
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        shiftRepository.deleteAll();
    }

    private String openShiftJson(String openingFloat) {
        return "{\"openingFloat\":" + openingFloat + "}";
    }

    private long openShift(String token, String openingFloat) throws Exception {
        var response = mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(openShiftJson(openingFloat)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("id").asLong();
    }

    @Test
    void openShift_thenGetCurrent_reflectsIt() throws Exception {
        openShift(cashierAToken, "500.00");

        mockMvc.perform(get("/shifts/current").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openingFloat").value(500.00))
                .andExpect(jsonPath("$.cashierId").value(AuthTestHelper.CASHIER_A_ID));
    }

    @Test
    void getCurrent_withNoOpenShift_returns404() throws Exception {
        mockMvc.perform(get("/shifts/current").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void openingSecondShiftWhileOneOpen_returns409() throws Exception {
        openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/open").header("Authorization", "Bearer " + cashierAToken)
                        .contentType(MediaType.APPLICATION_JSON).content(openShiftJson("300.00")))
                .andExpect(status().isConflict());
    }

    @Test
    void closeOwnShift_setsClosedStatusAndTimestamp() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + cashierAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").exists());
    }

    @Test
    void cashierClosingAnotherCashiersShift_returns403() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + cashierBToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCloseAnyCashiersShift() throws Exception {
        var shiftId = openShift(cashierAToken, "500.00");

        mockMvc.perform(post("/shifts/" + shiftId + "/close").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void openShift_withoutAuth_unauthorized() throws Exception {
        mockMvc.perform(post("/shifts/open").contentType(MediaType.APPLICATION_JSON).content(openShiftJson("500.00")))
                .andExpect(status().isUnauthorized());
    }
}
