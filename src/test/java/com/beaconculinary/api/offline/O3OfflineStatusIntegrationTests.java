package com.beaconculinary.api.offline;

import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Till;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.beaconculinary.api.support.O3Tills.body;
import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O3: POST /pos/offline/status, and the offline fields of GET /admin/tills and GET /pos/till-status. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class O3OfflineStatusIntegrationTests {
    private static final String PREFIX = "O3 Status ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OfflineProperties properties;

    private O3Tills tills;
    private String adminPwd;
    private Till t1;
    private Till t2;

    @BeforeEach
    void setUp() throws Exception {
        clock.setTime(LocalTime.of(12, 30));
        properties.setEnabled(true);
        adminPwd = AuthTestHelper.loginAsAdmin(mockMvc);
        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminPwd);
        t1 = tills.registerTill();
        t2 = tills.registerTill();
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
        tills.cleanUp();
        clock.reset();
    }

    // ---- storing reports ----

    @Test
    void aReport_isStored_withTheClockSkew_andTheNextOneReplacesIt() throws Exception {
        var now = clock.instant();
        var first = report(t1, Map.of(
                "pendingCount", 3, "oldestPendingSoldAt", now.minusSeconds(1800).toString(),
                "lastSyncAt", now.minusSeconds(2400).toString(), "offlineSince", now.minusSeconds(2000).toString(),
                "tillClockUtc", now.plusMillis(90_000).toString(), "appVersion", "1.8.0",
                "pending", List.of(entry(412, LocalDate.now(clock), 2), entry(413, LocalDate.now(clock), 1))));

        assertThat(first.getResponse().getStatus()).as(first.getResponse().getContentAsString()).isEqualTo(200);
        var ack = body(first);
        assertThat(ack.get("clockSkewMs").asLong()).isEqualTo(90_000);
        assertThat(Instant.parse(ack.get("receivedAt").asText())).isEqualTo(now);
        assertThat(ack.get("serverTimeUtc").asText()).endsWith("Z");
        var row = jdbc.queryForMap("SELECT * FROM [offline_till_status] WHERE [device_id] = ?", t1.id());
        assertThat(row.get("pending_count")).isEqualTo(3);
        assertThat(row.get("clock_skew_ms")).isEqualTo(90_000L);
        assertThat(row.get("app_version")).isEqualTo("1.8.0");
        assertThat(row.get("offline_since")).isNotNull();
        assertThat(pendingRows(t1)).isEqualTo(2);

        var second = report(t1, simple(0, clock.instant().minusMillis(2_500)));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second).get("clockSkewMs").asLong()).isEqualTo(-2_500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [offline_till_status] WHERE [device_id] = ?", Integer.class, t1.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT [pending_count] FROM [offline_till_status] WHERE [device_id] = ?",
                Integer.class, t1.id())).isZero();
        assertThat(pendingRows(t1)).isZero();
    }

    @Test
    void reports_needNoSignIn_andAreAcceptedWhileOfflineIsDisabled() throws Exception {
        properties.setEnabled(false);

        assertThat(report(t1, simple(0, clock.instant())).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void withoutARegisteredTill_returns403() throws Exception {
        assertError(report(null, simple(0, clock.instant())), 403, "DEVICE_NOT_REGISTERED");

        mockMvc.perform(post("/admin/devices/" + t2.id() + "/revoke").header("Authorization", "Bearer " + adminPwd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}")).andReturn();
        assertError(report(t2, simple(0, clock.instant())), 403, "DEVICE_NOT_REGISTERED");
    }

    @Test
    void invalidReports_return400() throws Exception {
        var day = LocalDate.now(clock);
        var now = clock.instant().toString();

        var mismatch = report(t1, Map.of("pendingCount", 3, "tillClockUtc", now, "pending", List.of(entry(1, day, 2))));
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(mismatch).get("pendingCountConsistent").asText()).isEqualTo("The pending counts must add up to pendingCount.");

        var duplicate = report(t1, Map.of("pendingCount", 2, "tillClockUtc", now,
                "pending", List.of(entry(1, day, 1), entry(1, day, 1))));
        assertThat(body(duplicate).has("pendingUnique")).isTrue();

        var tooMany = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(entry(i + 1, day, 1));
        }
        assertThat(body(report(t1, Map.of("pendingCount", 21, "tillClockUtc", now, "pending", tooMany))).has("pending")).isTrue();

        assertThat(body(report(t1, Map.of("pendingCount", 0, "tillClockUtc", now,
                "pending", List.of(entry(1, day, 0))))).has("pending[0].count")).isTrue();
        assertThat(body(report(t1, Map.of("pendingCount", 0, "pending", List.of()))).has("tillClockUtc")).isTrue();
        assertThat(body(report(t1, Map.of("pendingCount", -1, "tillClockUtc", now, "pending", List.of()))).has("pendingCount")).isTrue();
        assertThat(body(report(t1, Map.of("pendingCount", 0, "tillClockUtc", now))).has("pending")).isTrue();
        assertThat(body(report(t1, Map.of("pendingCount", 0, "tillClockUtc", now, "pending", List.of(),
                "appVersion", "v".repeat(51)))).has("appVersion")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM [offline_till_status] WHERE [device_id] = ?", Integer.class, t1.id()))
                .isZero();
    }

    @Test
    void aNonJsonReport_isRefused() throws Exception {
        var result = mockMvc.perform(on(post("/pos/offline/status"), t1).contentType(MediaType.TEXT_PLAIN)
                .content("{\"pendingCount\":0}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(415);
    }

    // ---- GET /admin/tills ----

    @Test
    void adminTills_showEachTillsOfflineState() throws Exception {
        assertThat(offline(t1).get("state").asText()).isEqualTo("NEVER_REPORTED");
        assertThat(offline(t1).get("pendingCount").isNull()).isTrue();

        report(t1, simple(0, clock.instant()));
        assertThat(offline(t1).get("state").asText()).isEqualTo("OK");

        var day = LocalDate.now(clock);
        report(t1, Map.of("pendingCount", 4, "tillClockUtc", clock.instant().plusMillis(1520).toString(), "appVersion", "1.8.0",
                "offlineSince", clock.instant().minusSeconds(600).toString(), "pending", List.of(entry(412, day, 4))));
        var pending = offline(t1);
        assertThat(pending.get("state").asText()).isEqualTo("PENDING");
        assertThat(pending.get("pendingCount").asInt()).isEqualTo(4);
        assertThat(pending.get("clockSkewMs").asLong()).isEqualTo(1520);
        assertThat(pending.get("appVersion").asText()).isEqualTo("1.8.0");
        assertThat(pending.get("lastReportAt").asText()).endsWith("Z");
        assertThat(pending.get("offlineSince").asText()).endsWith("Z");

        clock.setInstant(clock.instant().plusSeconds(11 * 60));
        var stale = offline(t1);
        assertThat(stale.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(stale.get("pendingCount").asInt()).isEqualTo(4);

        assertThat(offline(t2).get("state").asText()).isEqualTo("NEVER_REPORTED");
    }

    @Test
    void justUnderTheStaleLimit_aTillIsStillKnown() throws Exception {
        report(t1, simple(0, clock.instant()));
        clock.setInstant(clock.instant().plusSeconds(10 * 60 - 1));

        assertThat(offline(t1).get("state").asText()).isEqualTo("OK");
    }

    // ---- GET /pos/till-status ----

    @Test
    void tillStatus_carriesTheOfflineSettings_forEveryBrowser() throws Exception {
        for (var till : new Till[]{t1, null}) {
            var status = body(mockMvc.perform(on(get("/pos/till-status"), till)).andReturn());
            assertThat(status.get("offlineEnabled").asBoolean()).isTrue();
            assertThat(status.get("maxOfflineHours").asInt()).isEqualTo(6);
            assertThat(status.get("warnOfflineHours").asInt()).isEqualTo(5);
        }
        properties.setEnabled(false);
        assertThat(body(mockMvc.perform(on(get("/pos/till-status"), t1)).andReturn()).get("offlineEnabled").asBoolean()).isFalse();
    }

    // ---- helpers ----

    private MvcResult report(Till till, Map<String, Object> body) throws Exception {
        return mockMvc.perform(on(post("/pos/offline/status"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body))).andReturn();
    }

    private static Map<String, Object> simple(int pendingCount, Instant tillClock) {
        var body = new HashMap<String, Object>();
        body.put("pendingCount", pendingCount);
        body.put("tillClockUtc", tillClock.toString());
        body.put("pending", List.of());
        return body;
    }

    private static Map<String, Object> entry(long shiftId, LocalDate day, int count) {
        return Map.of("shiftId", shiftId, "tradingDay", day.toString(), "count", count);
    }

    private int pendingRows(Till till) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM [offline_till_pending] WHERE [device_id] = ?", Integer.class, till.id());
    }

    private JsonNode offline(Till till) throws Exception {
        var result = mockMvc.perform(get("/admin/tills").header("Authorization", "Bearer " + adminPwd)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        for (var row : body(result)) {
            if (till.id().equals(row.get("deviceId").asText())) {
                return row.get("offline");
            }
        }
        throw new AssertionError("till not listed");
    }

    private static void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(body(result).get("code").asText()).isEqualTo(code);
    }
}
