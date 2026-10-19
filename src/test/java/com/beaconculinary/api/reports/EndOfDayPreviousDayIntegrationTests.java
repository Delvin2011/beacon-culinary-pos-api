package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reports R3 Close Day scenario 9, with the first trading day set empty: the fixture's Sat
 * 26 Sep shift must be closed before Monday, so Saturday becomes Z-0001 and Monday Z-0002. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@TestPropertySource(properties = "reports.close-day.first-trading-day=")
class EndOfDayPreviousDayIntegrationTests {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private String adminToken;
    private ReportFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        clock.setInstant(TUESDAY.atTime(13, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        adminToken = AuthTestHelper.loginAsAdmin(mockMvc);
        ReportFixture.clear(jdbc);
        fixture = ReportFixture.seed(jdbc, AuthTestHelper.cashierAId(mockMvc), AuthTestHelper.cashierBId(mockMvc),
                AuthTestHelper.adminId(mockMvc));
    }

    @AfterEach
    void tearDown() {
        clock.reset();
        ReportFixture.clear(jdbc);
    }

    private ResultActions post_(String path) throws Exception {
        return mockMvc.perform(post(path).header("Authorization", "Bearer " + adminToken));
    }

    @Test
    void earlierUnclosedDay_blocksMonday_untilClosedFirst() throws Exception {
        post_("/admin/shifts/" + fixture.s1 + "/signoff").andExpect(status().isOk());
        post_("/admin/shifts/" + fixture.s2 + "/signoff").andExpect(status().isOk());

        post_("/admin/days/2026-10-05/close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[*].code", hasItem("PREVIOUS_DAY_NOT_CLOSED")))
                .andExpect(jsonPath("$.blockers[0].date").value("2026-09-26"));

        post_("/admin/shifts/" + fixture.sPreRange + "/signoff").andExpect(status().isOk());
        post_("/admin/days/2026-09-26/close").andExpect(status().isOk()).andExpect(jsonPath("$.meta.reference").value("Z-0001"));
        post_("/admin/days/2026-10-05/close").andExpect(status().isOk()).andExpect(jsonPath("$.meta.reference").value("Z-0002"));
    }
}
