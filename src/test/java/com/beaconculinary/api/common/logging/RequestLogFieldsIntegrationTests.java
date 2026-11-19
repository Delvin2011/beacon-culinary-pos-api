package com.beaconculinary.api.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.O3Tills;
import com.beaconculinary.api.support.O3Tills.Till;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beaconculinary.api.support.O3Tills.on;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T0-1 step 2: the user, session and till fields on the request log line, and no secret in any log
 * output across sign-in, PIN sign-in, refresh, the offline grant and offline sync.
 *
 * <p>Failure messages name the secret's kind, never its value.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestLogFieldsIntegrationTests {
    private static final String PREFIX = "T01 Log ";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MutableClock clock;
    @Autowired private OfflineProperties offline;

    private O3Tills tills;
    private ListAppender<ILoggingEvent> requestLog;

    @BeforeEach
    void setUp() {
        clock.setTime(LocalTime.of(12, 0));
        offline.setEnabled(true);
        requestLog = new ListAppender<>();
        requestLog.start();
        ((Logger) LoggerFactory.getLogger(RequestContextFilter.class)).addAppender(requestLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(RequestContextFilter.class)).detachAppender(requestLog);
        offline.setEnabled(false);
        if (tills != null) {
            tills.cleanUp();
        }
    }

    @Test
    void theRequestLogLine_carriesUserRoleSessionDeviceAndTill_andTheNextRequestStartsClean() throws Exception {
        tills = new O3Tills(mockMvc, jdbc, PREFIX, AuthTestHelper.loginAsAdmin(mockMvc));
        var cashier = tills.createCashier();
        var till = tills.registerTill();
        var token = tills.pinLogin(cashier, till);

        mockMvc.perform(on(get("/pos/till-status"), till).header("Authorization", "Bearer " + token)).andReturn();
        var signedIn = lastRequestEvent("/pos/till-status");
        assertThat(signedIn.getMDCPropertyMap())
                .containsEntry("tenantId", "default")
                .containsEntry("userId", Long.toString(cashier.id()))
                .containsEntry("role", "CASHIER")
                .containsEntry("deviceId", till.id())
                .containsEntry("tillCode", till.code())
                .containsKey("sessionId")
                .containsKey("requestId");

        mockMvc.perform(get("/menu/today")).andReturn();
        var anonymous = lastRequestEvent("/menu/today");
        assertThat(anonymous.getMDCPropertyMap())
                .containsKey("requestId")
                .doesNotContainKeys("userId", "role", "sessionId", "deviceId", "tillCode");
    }

    @Test
    void signInPinSignInRefreshGrantAndSync_logNoSecret(CapturedOutput output) throws Exception {
        var secrets = new LinkedHashMap<String, String>();

        // Password sign-in (the seeded test admin's password).
        var adminLogin = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("email", AuthTestHelper.ADMIN_EMAIL, "password", "654321"))))
                .andReturn();
        var adminToken = O3Tills.token(adminLogin);
        secrets.put("admin password", "654321");
        secrets.put("admin access token", adminToken);
        secrets.put("admin refresh cookie", refreshCookie(adminLogin));

        tills = new O3Tills(mockMvc, jdbc, PREFIX, adminToken);
        var cashier = tills.createCashier();
        var till = tills.registerTill();
        secrets.put("cashier PIN", cashier.pin());
        secrets.put("device cookie", till.cookie().getValue());

        // PIN sign-in on the till, then a wrong PIN.
        var pinLogin = mockMvc.perform(on(post("/auth/pin-login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashier.id(), "pin", cashier.pin())))).andReturn();
        var cashierToken = O3Tills.token(pinLogin);
        var cashierRefresh = refreshCookie(pinLogin);
        secrets.put("cashier access token", cashierToken);
        secrets.put("cashier refresh cookie", cashierRefresh);
        var wrongPin = cashier.pin().equals("000000") ? "111111" : "000000";
        mockMvc.perform(on(post("/auth/pin-login"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("cashierId", cashier.id(), "pin", wrongPin)))).andReturn();

        // Refresh with the refresh cookie.
        var refreshed = mockMvc.perform(on(post("/auth/refresh"), till).cookie(new Cookie("refreshToken", cashierRefresh)))
                .andReturn();
        assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
        secrets.put("refreshed access token", body(refreshed).get("token").asText());
        cashierToken = body(refreshed).get("token").asText();

        // Offline grant and sync.
        var shiftId = tills.openShift(cashierToken, till);
        var grantResult = mockMvc.perform(on(post("/pos/offline/grant"), till)
                .header("Authorization", "Bearer " + cashierToken)).andReturn();
        assertThat(grantResult.getResponse().getStatus()).isEqualTo(201);
        var grant = body(grantResult);
        secrets.put("offline grant", grant.get("grant").asText());
        var synced = mockMvc.perform(on(post("/pos/offline/sales"), till).contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(Map.of("grants", List.of(grant.get("grant").asText()),
                        "sales", List.of(sale(grant, shiftId, cashier.id())))))).andReturn();
        assertThat(synced.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(synced).get("results").get(0).get("result").asText()).isEqualTo("INVALID");

        var logs = output.getAll();
        for (var secret : secrets.entrySet()) {
            assertThat(secret.getValue()).as(secret.getKey() + " was captured").isNotBlank();
            assertThat(logs.contains(secret.getValue())).as(secret.getKey() + " appears in the log output").isFalse();
        }
        assertThat(logs).doesNotContain("Bearer ").doesNotContain("refreshToken=").doesNotContain("deviceToken=");
        assertThat(logs).contains("request POST /auth/login status=200", "request POST /auth/pin-login status=200",
                "request POST /auth/refresh status=200", "request POST /pos/offline/sales status=200");
    }

    private ILoggingEvent lastRequestEvent(String path) {
        var events = new ArrayList<>(requestLog.list);
        for (int i = events.size() - 1; i >= 0; i--) {
            if (path.equals(events.get(i).getMDCPropertyMap().get("path"))) {
                return events.get(i);
            }
        }
        throw new AssertionError("No request log line for " + path);
    }

    private static String refreshCookie(MvcResult result) {
        for (var header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith("refreshToken=")) {
                return header.substring("refreshToken=".length(), header.indexOf(';'));
            }
        }
        throw new AssertionError("No refresh cookie");
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    /** A sale with an unknown payment method: the grant is checked, then the sale is INVALID and
     * nothing is recorded (no order or exception rows to clean up). */
    private Map<String, Object> sale(JsonNode grant, long shiftId, long cashierId) {
        var sale = new HashMap<String, Object>();
        sale.put("clientOrderId", UUID.randomUUID().toString());
        sale.put("grantId", grant.get("grantId").asText());
        sale.put("offlineSeq", grant.get("nextOfflineSeq").asInt());
        sale.put("offlineReceiptNumber", "T9-LOG-0001");
        sale.put("collectionNumber", "T9-1");
        sale.put("soldAt", clock.instant().toString());
        sale.put("tillClockAtSendUtc", clock.instant().toString());
        sale.put("shiftId", shiftId);
        sale.put("cashierId", cashierId);
        sale.put("unlockedOffline", false);
        sale.put("snapshotVersion", "test");
        sale.put("lines", List.of(Map.of("dailyMealOptionId", 0, "name", "Nothing", "station", "KITCHEN", "quantity", 1,
                "unitPrice", new BigDecimal("10.00"), "lineTotal", new BigDecimal("10.00"), "extras", List.of())));
        sale.put("subtotal", new BigDecimal("10.00"));
        sale.put("total", new BigDecimal("10.00"));
        sale.put("vatRate", new BigDecimal("0.1500"));
        sale.put("vatIncludedAmount", new BigDecimal("1.30"));
        sale.put("payments", List.of(Map.of("method", "VOUCHER", "amount", new BigDecimal("10.00"),
                "amountTendered", new BigDecimal("10.00"), "changeDue", BigDecimal.ZERO)));
        return sale;
    }
}
