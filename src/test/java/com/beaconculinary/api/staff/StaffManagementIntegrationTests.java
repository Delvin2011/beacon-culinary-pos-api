package com.beaconculinary.api.staff;

import com.beaconculinary.api.admin.AuthorizationTokenRepository;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.users.Role;
import com.beaconculinary.api.users.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff management (/admin/staff): role-driven credentials, validation, role changes,
 * deactivation, resets, access, and the lock-down of the old /users endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class StaffManagementIntegrationTests {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuthorizationTokenRepository authorizationTokenRepository;

    private String admin;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @AfterEach
    void tearDown() {
        authorizationTokenRepository.deleteAll();
        createdIds.forEach(userRepository::deleteById);
        createdIds.clear();
    }

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private long created(Map<String, Object> body) throws Exception {
        var json = create(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = MAPPER.readTree(json).get("id").asLong();
        createdIds.add(id);
        return id;
    }

    private ResultActions update(long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/admin/staff/" + id).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(body)));
    }

    private int emailLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    private int pinLogin(long id, String pin) throws Exception {
        return mockMvc.perform(post("/auth/pin-login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("cashierId", id, "pin", pin))))
                .andReturn().getResponse().getStatus();
    }

    private String token(String email, String password) throws Exception {
        var json = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(json).get("token").asText();
    }

    @Test
    void rolesEndpoint_describesTheCredentialsEachRoleUses() throws Exception {
        mockMvc.perform(get("/admin/staff/roles").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].role", contains("ADMIN", "CASHIER", "KITCHEN", "BARISTA", "HEAD_CHEF", "STOCK_ADMIN", "STOCK_CLERK")))
                .andExpect(jsonPath("$[?(@.role == 'CASHIER')].password").value("NONE"))
                .andExpect(jsonPath("$[?(@.role == 'CASHIER')].pin").value("REQUIRED"))
                .andExpect(jsonPath("$[?(@.role == 'HEAD_CHEF')].password").value("REQUIRED"))
                .andExpect(jsonPath("$[?(@.role == 'HEAD_CHEF')].pin").value("OPTIONAL"))
                .andExpect(jsonPath("$[?(@.role == 'ADMIN')].pin").value("REQUIRED"))
                .andExpect(jsonPath("$[?(@.role == 'STOCK_ADMIN')].pin").value("NONE"));
    }

    @Test
    void eachRoleIsCreatedWithItsCredentials_andCanLogInTheRightWay() throws Exception {
        var cashier = created(Map.of("name", "Staff Test Cashier", "role", "CASHIER", "pin", "246810"));
        assertThat(pinLogin(cashier, "246810")).isEqualTo(200);
        mockMvc.perform(get("/admin/staff/" + cashier).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.hasPin").value(true));
        mockMvc.perform(get("/users/login-tiles?screen=POS")).andExpect(jsonPath("$[*].name", hasItem("Staff Test Cashier")));

        var kitchen = created(Map.of("name", "Staff Test Kitchen", "role", "KITCHEN", "pin", "135791"));
        assertThat(pinLogin(kitchen, "135791")).isEqualTo(200);

        created(Map.of("name", "Staff Test Chef", "role", "HEAD_CHEF", "email", "Staff.Chef@Canteen.Local", "password", "chefpass1"));
        // Email is stored lowercase; no PIN, so not on the kitchen tiles.
        assertThat(emailLogin("staff.chef@canteen.local", "chefpass1")).isEqualTo(200);
        mockMvc.perform(get("/users/login-tiles?screen=KITCHEN")).andExpect(jsonPath("$[*].name", not(hasItem("Staff Test Chef"))));

        created(Map.of("name", "Staff Test Clerk", "role", "STOCK_CLERK", "email", "staff.clerk@canteen.local", "password", "clerkpass1"));
        assertThat(emailLogin("staff.clerk@canteen.local", "clerkpass1")).isEqualTo(200);

        created(Map.of("name", "Staff Test Admin", "role", "ADMIN", "email", "staff.admin@canteen.local",
                "password", "adminpass1", "pin", "975310"));
        assertThat(emailLogin("staff.admin@canteen.local", "adminpass1")).isEqualTo(200);
        // The new admin's PIN works as a manager approval at the till.
        mockMvc.perform(post("/admin/authorize").header("Authorization", "Bearer " + AuthTestHelper.loginAsCashier(mockMvc))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"975310\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void credentialsAreValidatedAgainstTheRole() throws Exception {
        create(Map.of("name", "X", "role", "CASHIER", "pin", "123456", "password", "secret12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").value("Cashier accounts don't use a password."));
        create(Map.of("name", "X", "role", "CASHIER", "pin", "123456", "email", "x@canteen.local"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Cashier accounts log in by PIN and don't have an email."));
        create(Map.of("name", "X", "role", "CASHIER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.pin").value("PIN is required for Cashier."));
        create(Map.of("name", "X", "role", "KITCHEN", "pin", "12345"))
                .andExpect(jsonPath("$.pin").value("PIN must be exactly 6 digits."));
        create(Map.of("name", "X", "role", "KITCHEN", "pin", "12a456"))
                .andExpect(jsonPath("$.pin").value("PIN must be exactly 6 digits."));
        create(Map.of("name", "X", "role", "HEAD_CHEF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Email is required for Head Chef."))
                .andExpect(jsonPath("$.password").value("Password is required for Head Chef."));
        create(Map.of("name", "X", "role", "STOCK_ADMIN", "email", "x@canteen.local", "password", "secret12", "pin", "123456"))
                .andExpect(jsonPath("$.pin").value("Stock Admin accounts don't use a pin."));
        create(Map.of("name", "X", "role", "STOCK_CLERK", "email", "x@canteen.local", "password", "abc"))
                .andExpect(jsonPath("$.password").value("Password must be between 6 and 25 characters."));
        create(Map.of("name", "X", "role", "STOCK_CLERK", "email", "not-an-email", "password", "secret12"))
                .andExpect(jsonPath("$.email").value("Email must be a valid email address."));
        create(Map.of("name", "X", "role", "USER", "email", "x@canteen.local", "password", "secret12"))
                .andExpect(jsonPath("$.role").exists());
        create(Map.of("role", "CASHIER", "pin", "123456"))
                .andExpect(jsonPath("$.name").value("Name is required."));
    }

    @Test
    void duplicateEmailsAndAdminPinsAreRefused_butCashiersMayShareAPin() throws Exception {
        create(Map.of("name", "X", "role", "STOCK_CLERK", "email", AuthTestHelper.ADMIN_EMAIL, "password", "secret12"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_IN_USE"));
        // The seeded admin's PIN is 654321.
        create(Map.of("name", "X", "role", "ADMIN", "email", "dup.pin@canteen.local", "password", "secret12", "pin", "654321"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_IN_USE"));
        created(Map.of("name", "Staff Shared Pin A", "role", "CASHIER", "pin", "111222"));
        created(Map.of("name", "Staff Shared Pin B", "role", "CASHIER", "pin", "111222"));
    }

    @Test
    void changingRole_requiresMissingCredentials_andClearsOnesTheNewRoleDoesntUse() throws Exception {
        var id = created(Map.of("name", "Staff Mover", "role", "CASHIER", "pin", "314159"));

        update(id, Map.of("name", "Staff Mover", "role", "HEAD_CHEF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.password").exists());
        update(id, Map.of("name", "Staff Mover", "role", "HEAD_CHEF", "email", "staff.mover@canteen.local", "password", "moverpass"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HEAD_CHEF"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.hasPin").value(true));   // optional for Head Chef, so kept
        assertThat(emailLogin("staff.mover@canteen.local", "moverpass")).isEqualTo(200);

        // Back to cashier: email and password are cleared, so back-office login stops working.
        update(id, Map.of("name", "Staff Mover", "role", "CASHIER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.hasPassword").value(false));
        assertThat(emailLogin("staff.mover@canteen.local", "moverpass")).isEqualTo(401);
        assertThat(pinLogin(id, "314159")).isEqualTo(200);

        // Promoting to admin always needs a (unique) PIN, plus the email and password admins use.
        update(id, Map.of("name", "Staff Mover", "role", "ADMIN", "email", "staff.mover@canteen.local", "password", "moverpass"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.pin").value("PIN is required for Admin (Manager)."));
        update(id, Map.of("name", "Staff Mover", "role", "ADMIN", "email", "staff.mover@canteen.local", "password", "moverpass",
                "pin", "271828"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void deactivatedStaffCantLogIn_andTheLastAdminCantBeDeactivated() throws Exception {
        var cashier = created(Map.of("name", "Staff Leaver", "role", "CASHIER", "pin", "161803"));
        created(Map.of("name", "Staff Leaver Chef", "role", "HEAD_CHEF", "email", "staff.leaver@canteen.local", "password", "leaverpass"));
        var chef = createdIds.get(createdIds.size() - 1);

        update(cashier, Map.of("name", "Staff Leaver", "role", "CASHIER", "active", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(pinLogin(cashier, "161803")).isEqualTo(401);
        mockMvc.perform(get("/users/login-tiles?screen=POS")).andExpect(jsonPath("$[*].name", not(hasItem("Staff Leaver"))));

        update(chef, Map.of("name", "Staff Leaver Chef", "role", "HEAD_CHEF", "active", false)).andExpect(status().isOk());
        assertThat(emailLogin("staff.leaver@canteen.local", "leaverpass")).isEqualTo(401);

        var activeAdmins = userRepository.findByActiveTrueAndRoleIn(List.of(Role.ADMIN));
        Assumptions.assumeTrue(activeAdmins.size() == 1, "only meaningful when the seeded admin is the only active admin");
        var onlyAdmin = activeAdmins.get(0);
        update(onlyAdmin.getId(), Map.of("name", onlyAdmin.getName(), "role", "ADMIN", "active", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        update(onlyAdmin.getId(), Map.of("name", onlyAdmin.getName(), "role", "STOCK_ADMIN"))
                .andExpect(status().isConflict());
    }

    @Test
    void passwordAndPinResets_followTheRole() throws Exception {
        var clerk = created(Map.of("name", "Staff Reset Clerk", "role", "STOCK_CLERK", "email", "staff.reset@canteen.local", "password", "oldpass12"));
        var cashier = created(Map.of("name", "Staff Reset Cashier", "role", "CASHIER", "pin", "102030"));

        mockMvc.perform(post("/admin/staff/" + clerk + "/password").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"newpass12\"}"))
                .andExpect(status().isOk());
        assertThat(emailLogin("staff.reset@canteen.local", "newpass12")).isEqualTo(200);

        mockMvc.perform(post("/admin/staff/" + cashier + "/pin").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"405060\"}"))
                .andExpect(status().isOk());
        assertThat(pinLogin(cashier, "405060")).isEqualTo(200);

        mockMvc.perform(post("/admin/staff/" + cashier + "/password").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"whatever1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.password").value("Cashier accounts don't use a password."));
        mockMvc.perform(post("/admin/staff/" + clerk + "/pin").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyAdminsManageStaff() throws Exception {
        for (var token : List.of(AuthTestHelper.loginAsStockAdmin(mockMvc), AuthTestHelper.loginAsHeadChef(mockMvc),
                AuthTestHelper.loginAsCashier(mockMvc))) {
            mockMvc.perform(get("/admin/staff").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            mockMvc.perform(post("/admin/staff").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/staff").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].role", not(hasItem("USER"))))
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].pinHash").doesNotExist());
    }

    @Test
    void oldUsersEndpointsAreLockedDown() throws Exception {
        var cashier = AuthTestHelper.loginAsCashier(mockMvc);
        var someoneElse = AuthTestHelper.adminId(mockMvc);

        mockMvc.perform(get("/users").header("Authorization", "Bearer " + cashier)).andExpect(status().isForbidden());
        mockMvc.perform(get("/users/" + someoneElse).header("Authorization", "Bearer " + cashier)).andExpect(status().isForbidden());
        mockMvc.perform(put("/users/" + someoneElse).header("Authorization", "Bearer " + cashier)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/users/" + someoneElse).header("Authorization", "Bearer " + cashier)).andExpect(status().isForbidden());
        // The old public sign-up is gone.
        assertThat(mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"email\":\"signup@canteen.local\",\"password\":\"secret12\"}"))
                .andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    @Test
    void changePassword_isOwnOnly_andStoresAHash() throws Exception {
        var id = created(Map.of("name", "Staff Changer", "role", "STOCK_CLERK", "email", "staff.changer@canteen.local", "password", "firstpass"));
        var token = token("staff.changer@canteen.local", "firstpass");

        mockMvc.perform(post("/users/" + id + "/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"firstpass\",\"newPassword\":\"secondpass\"}"))
                .andExpect(status().isOk());
        // Works only because the new password is now hashed (it used to be stored as plain text).
        assertThat(emailLogin("staff.changer@canteen.local", "secondpass")).isEqualTo(200);

        mockMvc.perform(post("/users/" + AuthTestHelper.adminId(mockMvc) + "/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"654321\",\"newPassword\":\"takeover1\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/users/" + id + "/change-password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"oldPassword\":\"secondpass\",\"newPassword\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.newPassword").exists());
    }
}
