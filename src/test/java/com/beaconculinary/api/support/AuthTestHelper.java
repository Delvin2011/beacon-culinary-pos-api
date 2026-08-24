package com.beaconculinary.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Logs in as the seeded CASHIER/ADMIN users (V13__seed_menu_and_users.sql,
 * V14__add_pin_auth_to_users.sql) via the real /auth/login and /auth/pin-login flows.
 */
public class AuthTestHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final long CASHIER_A_ID = 2L;
    public static final long ADMIN_ID = 3L;
    public static final long CASHIER_B_ID = 4L;
    public static final long KITCHEN_ID = 5L;

    public static String loginAsCashier(MockMvc mockMvc) throws Exception {
        return login(mockMvc, "cashier@canteen.local", "654321");
    }

    public static String loginAsAdmin(MockMvc mockMvc) throws Exception {
        return login(mockMvc, "admin@canteen.local", "654321");
    }

    public static String loginAsStockClerk(MockMvc mockMvc) throws Exception {
        return login(mockMvc, "stockclerk@canteen.local", "654321");
    }

    public static String loginAsStockAdmin(MockMvc mockMvc) throws Exception {
        return login(mockMvc, "stockadmin@canteen.local", "654321");
    }

    private static String login(MockMvc mockMvc, String email, String password) throws Exception {
        var body = MAPPER.writeValueAsString(new LoginRequest(email, password));
        var response = mockMvc.perform(post("/auth/login").contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("token").asText();
    }

    public static String loginAsKitchen(MockMvc mockMvc) throws Exception {
        return loginWithPin(mockMvc, KITCHEN_ID, "654321");
    }

    public static String loginWithPin(MockMvc mockMvc, long cashierId, String pin) throws Exception {
        var body = MAPPER.writeValueAsString(new PinLoginRequest(cashierId, pin));
        var response = mockMvc.perform(post("/auth/pin-login").contentType(APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).get("token").asText();
    }

    private record LoginRequest(String email, String password) {
    }

    private record PinLoginRequest(long cashierId, String pin) {
    }
}
