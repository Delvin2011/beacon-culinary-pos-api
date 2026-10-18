package com.beaconculinary.api.admin;

import com.beaconculinary.api.common.SecurityRules;
import com.beaconculinary.api.users.Role;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class AdminSecurityRules implements SecurityRules {
    @Override
    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
        // A cashier at the till submits whichever admin's PIN is presented — Stage 2.6's
        // manager-override flow, and Stage 3.3's management-session variant — so these two
        // /admin/** paths are deliberately not admin-only.
        registry
            .requestMatchers(HttpMethod.POST, "/admin/authorize", "/admin/authorize-session")
                .hasAnyRole(Role.CASHIER.name(), Role.ADMIN.name())
            // Head Chef: read-only on ingredients (stock and movements) and the menu catalog; full
            // daily planning (create/edit/confirm); reports, where each report's own allowedRoles
            // decides what may run (Sales Summary and Meal & Extras Sales for the Head Chef). These come before the
            // STOCK_ADMIN and ADMIN rules below because the first matching rule wins.
            // Read-only for the Head Chef: ingredients, GRVs (deliveries) and waste history. Capturing
            // or editing GRVs, direct waste posting and uploads stay STOCK_ADMIN/ADMIN (rule below).
            .requestMatchers(HttpMethod.GET,
                    "/admin/ingredients", "/admin/ingredients/**",
                    "/admin/grv", "/admin/grv/**",
                    "/admin/waste", "/admin/waste/**")
                .hasAnyRole(Role.HEAD_CHEF.name(), Role.STOCK_ADMIN.name(), Role.ADMIN.name())
            .requestMatchers(HttpMethod.GET,
                    "/admin/meal-catalog", "/admin/meal-catalog/**",
                    "/admin/component-catalog", "/admin/component-catalog/**")
                .hasAnyRole(Role.HEAD_CHEF.name(), Role.ADMIN.name())
            .requestMatchers(
                    "/admin/daily-options", "/admin/daily-options/**",
                    "/admin/daily-component-stock", "/admin/daily-component-stock/**",
                    "/admin/daily-planning/**",
                    "/admin/reports", "/admin/reports/**")
                .hasAnyRole(Role.HEAD_CHEF.name(), Role.ADMIN.name())
            // Stage 5 Revision: Ingredient Master, GRV, Stock Take, Purchase Orders, and direct
            // Waste move from ADMIN-only to STOCK_ADMIN (ADMIN still passes, as a superset).
            // Recipes/catalog/daily-planning stay ADMIN-only, falling through to the rule below.
            .requestMatchers(
                    "/admin/ingredients", "/admin/ingredients/**",
                    "/admin/count-sheet-categories", "/admin/count-sheet-categories/**",
                    "/admin/grv", "/admin/grv/**",
                    "/admin/stock-takes", "/admin/stock-takes/**",
                    "/admin/purchase-orders", "/admin/purchase-orders/**",
                    "/admin/waste", "/admin/waste/**",
                    // GRV VAT handling: the standard rate, for the GRV screen's live preview.
                    "/admin/settings/vat")
                .hasAnyRole(Role.STOCK_ADMIN.name(), Role.ADMIN.name())
            .requestMatchers("/admin/**").hasRole(Role.ADMIN.name());
    }
}
