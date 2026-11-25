package com.beaconculinary.api.support.tenancy;

import com.beaconculinary.api.support.AuthTestHelper;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.SpringBootMockMvcBuilderCustomizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T1-3, TESTS ONLY: requests at one customer's address in a multi-mode context. A MockMvc with the application's own
 * filters (as {@code @AutoConfigureMockMvc} builds it) whose every request carries {@code Host: <slug>.pos.localhost},
 * so the existing helpers (O3Tills, AuthTestHelper) work unchanged at a customer. And the seeded admin's account
 * ({@link AuthTestHelper#ADMIN_EMAIL}, its usual password) in a fixture database, which starts with no users.
 */
public final class CustomerMvc {
    private static final String ADMIN_PASSWORD = "654321";

    private CustomerMvc() {
    }

    public static MockMvc at(WebApplicationContext context, String slug) {
        var builder = MockMvcBuilders.webAppContextSetup(context);
        var customizer = new SpringBootMockMvcBuilderCustomizer(context);
        // The shared MockMvc already prints on failure; a second printer can't be registered in the same context.
        customizer.setPrint(MockMvcPrint.NONE);
        customizer.customize(builder);
        builder.defaultRequest(get("/").header("Host", slug + ".pos.localhost"));
        return builder.build();
    }

    /** Adds the admin account to this customer's database once (the baseline has no users). */
    public static void ensureAdmin(JdbcTemplate customerDatabase) {
        var exists = customerDatabase.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class,
                AuthTestHelper.ADMIN_EMAIL);
        if (exists == 0) {
            customerDatabase.update("INSERT INTO users (name, email, password, role, active) VALUES (?, ?, ?, 'ADMIN', 1)",
                    "Canteen Admin", AuthTestHelper.ADMIN_EMAIL, new BCryptPasswordEncoder().encode(ADMIN_PASSWORD));
        }
    }
}
