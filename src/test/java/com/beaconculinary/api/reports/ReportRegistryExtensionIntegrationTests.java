package com.beaconculinary.api.reports;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.users.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports R1 §1.3: a new report is only a ReportDefinition bean — these stubs are registered by
 * test configuration alone, with no change to the controller, catalogue or export.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReportRegistryExtensionIntegrationTests.StubReports.class)
class ReportRegistryExtensionIntegrationTests {
    @Autowired
    private MockMvc mockMvc;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
    }

    @Test
    void fourthReport_appearsRunsAndExports_withNoOtherChange() throws Exception {
        mockMvc.perform(get("/admin/reports").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[*].key", hasItem("stub-fourth")));
        mockMvc.perform(get("/admin/reports/stub-fourth?from=2026-10-01&to=2026-10-02").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("CONTROLS_STAFF"))
                .andExpect(jsonPath("$.rows[1].name").value("Second"))
                .andExpect(jsonPath("$.totals.amount").value(30.00))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
        mockMvc.perform(get("/admin/reports/stub-fourth/export?format=xlsx&from=2026-10-01&to=2026-10-02")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void allowedRoles_areEnforcedPerDefinition() throws Exception {
        mockMvc.perform(get("/admin/reports").header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$[*].key", not(hasItem("stub-kitchen-only"))));
        mockMvc.perform(get("/admin/reports/stub-kitchen-only").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/reports/stub-kitchen-only/export?format=csv").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportAboveTheRowCap_isRefused() throws Exception {
        mockMvc.perform(get("/admin/reports/stub-large/export?format=csv").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.export").value("This export has 50001 rows; the limit is 50000. Narrow the date range."));
    }

    @TestConfiguration
    static class StubReports {
        @Bean
        ReportDefinition stubFourth() {
            return new Stub("stub-fourth", ReportCategory.CONTROLS_STAFF, Set.of(Role.ADMIN), 2);
        }

        @Bean
        ReportDefinition stubKitchenOnly() {
            return new Stub("stub-kitchen-only", ReportCategory.KITCHEN_PLANNING, Set.of(Role.KITCHEN), 1);
        }

        @Bean
        ReportDefinition stubLarge() {
            return new Stub("stub-large", ReportCategory.SALES, Set.of(Role.ADMIN), 50_001);
        }
    }

    private record Stub(String key, ReportCategory category, Set<Role> allowedRoles, int rowCount) implements ReportDefinition {
        @Override
        public String title() {
            return "Stub " + key;
        }

        @Override
        public String description() {
            return "Test-only report.";
        }

        @Override
        public List<ReportParamSpec> params() {
            return List.of(ReportParamSpec.from(), ReportParamSpec.to());
        }

        @Override
        public int definitionVersion() {
            return 1;
        }

        @Override
        public List<String> notes() {
            return List.of("A stub.");
        }

        @Override
        public ReportResult run(ReportParams params) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var i = 0; i < rowCount; i++) {
                var row = new LinkedHashMap<String, Object>();
                row.put("name", i == 1 ? "Second" : "Row " + i);
                row.put("amount", ReportValues.money(BigDecimal.valueOf(10L * (i + 1))));
                rows.add(row);
            }
            var total = rows.stream().map(row -> (BigDecimal) row.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReportResult(
                    List.of(ReportColumn.of("name", "Name", ColumnType.TEXT, ColumnTotal.LABEL),
                            ReportColumn.of("amount", "Amount", ColumnType.MONEY, ColumnTotal.SUM)),
                    rows,
                    Map.of("name", "Total", "amount", total),
                    List.of(ReportCheck.compare("stub", "Stub check", total, total, null)));
        }
    }
}
