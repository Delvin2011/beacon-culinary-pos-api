package com.beaconculinary.api.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Logs in as the seeded CASHIER/ADMIN users (V13__seed_menu_and_users.sql,
 * V14__add_pin_auth_to_users.sql) via the real /auth/login and /auth/pin-login flows.
 *
 * <p>User ids are looked up by email rather than hard-coded: the seed migrations insert by email
 * and the ids come from the IDENTITY column, so they differ between databases (a database
 * seeded from scratch starts Cashier A at 1, an older one at 2).
 */
public class AuthTestHelper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String CASHIER_A_EMAIL = "cashier@canteen.local";
    public static final String ADMIN_EMAIL = "admin@canteen.local";
    public static final String CASHIER_B_EMAIL = "cashierb@canteen.local";
    public static final String KITCHEN_EMAIL = "kitchen@canteen.local";

    private static final Map<String, Long> USER_IDS_BY_EMAIL = new ConcurrentHashMap<>();

    public static long cashierAId(MockMvc mockMvc) throws Exception {
        return userId(mockMvc, CASHIER_A_EMAIL);
    }

    public static long adminId(MockMvc mockMvc) throws Exception {
        return userId(mockMvc, ADMIN_EMAIL);
    }

    public static long cashierBId(MockMvc mockMvc) throws Exception {
        return userId(mockMvc, CASHIER_B_EMAIL);
    }

    public static long kitchenId(MockMvc mockMvc) throws Exception {
        return userId(mockMvc, KITCHEN_EMAIL);
    }

    /** Resolved once per test JVM via GET /users as admin — the kitchen user is PIN-only, so
     * its id can't come from an email login. Seeded users are never deleted, so caching is safe. */
    public static long userId(MockMvc mockMvc, String email) throws Exception {
        if (USER_IDS_BY_EMAIL.isEmpty()) {
            var response = mockMvc.perform(get("/users").header("Authorization", "Bearer " + loginAsAdmin(mockMvc)))
                    .andReturn().getResponse().getContentAsString();
            for (var node : MAPPER.readTree(response)) {
                USER_IDS_BY_EMAIL.put(node.get("email").asText().toLowerCase(), node.get("id").asLong());
            }
        }
        var id = USER_IDS_BY_EMAIL.get(email.toLowerCase());
        if (id == null) {
            throw new IllegalStateException("Seeded user " + email + " not found in GET /users");
        }
        return id;
    }

    public static String loginAsCashier(MockMvc mockMvc) throws Exception {
        return login(mockMvc, CASHIER_A_EMAIL, "654321");
    }

    public static String loginAsAdmin(MockMvc mockMvc) throws Exception {
        return login(mockMvc, ADMIN_EMAIL, "654321");
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
        return loginWithPin(mockMvc, kitchenId(mockMvc), "654321");
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
