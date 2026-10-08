package com.beaconculinary.api.reports;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.support.AuthTestHelper;
import com.beaconculinary.api.support.ClockTestConfig;
import com.beaconculinary.api.support.MutableClock;
import com.beaconculinary.api.support.ReportFixture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.beaconculinary.api.support.ReportFixture.MONDAY;
import static com.beaconculinary.api.support.ReportFixture.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reports R1 acceptance: every figure in the spec's fixture tables, the checks, params,
 * access and export. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
class ReportsIntegrationTests {
    private static final String RANGE = "from=2026-10-05&to=2026-10-06";

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
        // Tuesday 13:00 Johannesburg: S3 (opened Tuesday 08:00) is still OPEN, not OVERDUE.
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

    private ResultActions run(String key, String query) throws Exception {
        return mockMvc.perform(get("/admin/reports/" + key + "?" + query).header("Authorization", "Bearer " + adminToken));
    }

    private ResultActions export(String key, String query) throws Exception {
        return mockMvc.perform(get("/admin/reports/" + key + "/export?" + query).header("Authorization", "Bearer " + adminToken));
    }

    @Test
    void catalogue_listsTheThreeReportsForAdmin() throws Exception {
        mockMvc.perform(get("/admin/reports").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", contains("cash-up-variance", "sales-summary", "tender-summary")))
                .andExpect(jsonPath("$[1].category").value("SALES"))
                .andExpect(jsonPath("$[1].template").value("TABLE"))
                .andExpect(jsonPath("$[1].params[0].name").value("from"))
                .andExpect(jsonPath("$[1].params[0].default").value("today-6"))
                .andExpect(jsonPath("$[1].params[2].options", contains("DAY", "WEEK", "MONTH", "HOUR")))
                .andExpect(jsonPath("$[2].params[2].options", contains("DAY", "WEEK", "MONTH")));
    }

    @Test
    void everyOtherRole_gets403_onCatalogueRunAndExport() throws Exception {
        var tokens = List.of(AuthTestHelper.loginAsCashier(mockMvc), AuthTestHelper.loginAsKitchen(mockMvc),
                AuthTestHelper.loginAsStockAdmin(mockMvc), AuthTestHelper.loginAsStockClerk(mockMvc));
        for (var token : tokens) {
            for (var url : List.of("/admin/reports", "/admin/reports/sales-summary", "/admin/reports/sales-summary/export?format=csv")) {
                mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void salesSummary_byDay_matchesFixture() throws Exception {
        run("sales-summary", RANGE + "&groupBy=DAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.key").value("sales-summary"))
                .andExpect(jsonPath("$.meta.amountsInclVat").value(true))
                .andExpect(jsonPath("$.meta.currency").value("ZAR"))
                .andExpect(jsonPath("$.meta.timezone").value("Africa/Johannesburg"))
                .andExpect(jsonPath("$.meta.params.groupBy").value("DAY"))
                .andExpect(jsonPath("$.meta.notes", hasItem("Amounts include VAT.")))
                .andExpect(jsonPath("$.page").value(nullValue()))
                .andExpect(jsonPath("$.columns[0].type").value("DATE"))
                .andExpect(jsonPath("$.columns[*].key", contains("period", "orders", "voidedOrRefundedOrders", "netOrders",
                        "grossSales", "voids", "refunds", "discounts", "extrasRemoved", "netSales", "averageOrderValue")))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].period").value("2026-10-05"))
                .andExpect(jsonPath("$.rows[0].orders").value(7))
                .andExpect(jsonPath("$.rows[0].voidedOrRefundedOrders").value(2))
                .andExpect(jsonPath("$.rows[0].netOrders").value(5))
                .andExpect(jsonPath("$.rows[0].grossSales").value(570.00))
                .andExpect(jsonPath("$.rows[0].voids").value(50.00))
                .andExpect(jsonPath("$.rows[0].refunds").value(90.00))
                .andExpect(jsonPath("$.rows[0].discounts").value(10.00))
                .andExpect(jsonPath("$.rows[0].extrasRemoved").value(0.00))
                .andExpect(jsonPath("$.rows[0].netSales").value(420.00))
                .andExpect(jsonPath("$.rows[0].averageOrderValue").value(84.00))
                .andExpect(jsonPath("$.rows[1].period").value("2026-10-06"))
                .andExpect(jsonPath("$.rows[1].orders").value(2))
                .andExpect(jsonPath("$.rows[1].netOrders").value(2))
                .andExpect(jsonPath("$.rows[1].grossSales").value(90.00))
                .andExpect(jsonPath("$.rows[1].extrasRemoved").value(15.00))
                .andExpect(jsonPath("$.rows[1].netSales").value(75.00))
                .andExpect(jsonPath("$.rows[1].averageOrderValue").value(37.50))
                .andExpect(jsonPath("$.totals.period").value("Total"))
                .andExpect(jsonPath("$.totals.orders").value(9))
                .andExpect(jsonPath("$.totals.voidedOrRefundedOrders").value(2))
                .andExpect(jsonPath("$.totals.netOrders").value(7))
                .andExpect(jsonPath("$.totals.grossSales").value(660.00))
                .andExpect(jsonPath("$.totals.voids").value(50.00))
                .andExpect(jsonPath("$.totals.refunds").value(90.00))
                .andExpect(jsonPath("$.totals.discounts").value(10.00))
                .andExpect(jsonPath("$.totals.extrasRemoved").value(15.00))
                .andExpect(jsonPath("$.totals.netSales").value(495.00))
                .andExpect(jsonPath("$.totals.averageOrderValue").value(70.71))
                .andExpect(jsonPath("$.checks[0].key").value("net-agrees-with-orders"))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"))
                .andExpect(jsonPath("$.checks[0].expected").value(495.00))
                .andExpect(jsonPath("$.checks[0].actual").value(495.00))
                .andExpect(jsonPath("$.checks[0].difference").value(0.00));
    }

    @Test
    void salesSummary_byHour_matchesFixture() throws Exception {
        run("sales-summary", RANGE + "&groupBy=HOUR").andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].type").value("TEXT"))
                .andExpect(jsonPath("$.rows[*].period", contains("07:00", "08:00", "09:00", "10:00", "11:00", "12:00")))
                .andExpect(jsonPath("$.rows[*].orders", contains(2, 2, 1, 1, 1, 2)))
                .andExpect(jsonPath("$.rows[*].grossSales", contains(160.0, 120.0, 50.0, 90.0, 120.0, 120.0)))
                .andExpect(jsonPath("$.rows[*].netSales", contains(160.0, 120.0, 0.0, 0.0, 120.0, 95.0)))
                .andExpect(jsonPath("$.totals.orders").value(9))
                .andExpect(jsonPath("$.totals.grossSales").value(660.00))
                .andExpect(jsonPath("$.totals.netSales").value(495.00))
                .andExpect(jsonPath("$.checks[0].status").value("PASS"));
    }

    @Test
    void salesSummary_byWeekAndMonth_labelsAsText() throws Exception {
        run("sales-summary", RANGE + "&groupBy=WEEK")
                .andExpect(jsonPath("$.columns[0].label").value("Week"))
                .andExpect(jsonPath("$.columns[0].type").value("TEXT"))
                .andExpect(jsonPath("$.rows[*].period", contains("Week of 2026-10-05")))
                .andExpect(jsonPath("$.rows[0].netSales").value(495.00))
                .andExpect(jsonPath("$.totals.averageOrderValue").value(70.71));
        run("sales-summary", RANGE + "&groupBy=MONTH")
                .andExpect(jsonPath("$.rows[*].period", contains("2026-10")))
                .andExpect(jsonPath("$.rows[0].orders").value(9));
    }

    @Test
    void tenderSummary_byDay_matchesFixture() throws Exception {
        run("tender-summary", RANGE + "&groupBy=DAY").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("PAYMENTS_ACCOUNTS"))
                .andExpect(jsonPath("$.rows[*].period", contains("2026-10-05", "2026-10-06")))
                .andExpect(jsonPath("$.rows[*].cash", contains(360.0, 40.0)))
                .andExpect(jsonPath("$.rows[*].card", contains(90.0, 0.0)))
                .andExpect(jsonPath("$.rows[*].account", contains(120.0, 50.0)))
                .andExpect(jsonPath("$.rows[*].totalCollected", contains(570.0, 90.0)))
                .andExpect(jsonPath("$.rows[*].cashPaidOut", contains(150.0, 0.0)))
                .andExpect(jsonPath("$.rows[*].accountCredits", contains(0.0, 15.0)))
                .andExpect(jsonPath("$.rows[*].netCashMovement", contains(210.0, 40.0)))
                .andExpect(jsonPath("$.rows[*].splitOrders", contains(1, 0)))
                .andExpect(jsonPath("$.totals.period").value("Total"))
                .andExpect(jsonPath("$.totals.cash").value(400.00))
                .andExpect(jsonPath("$.totals.card").value(90.00))
                .andExpect(jsonPath("$.totals.account").value(170.00))
                .andExpect(jsonPath("$.totals.totalCollected").value(660.00))
                .andExpect(jsonPath("$.totals.cashPaidOut").value(150.00))
                .andExpect(jsonPath("$.totals.accountCredits").value(15.00))
                .andExpect(jsonPath("$.totals.netCashMovement").value(250.00))
                .andExpect(jsonPath("$.totals.splitOrders").value(1))
                .andExpect(jsonPath("$.checks[*].key", contains("collected-equals-gross", "cash-matches-cashups")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS")))
                .andExpect(jsonPath("$.checks[1].expected").value(250.00))
                .andExpect(jsonPath("$.checks[1].actual").value(250.00));
    }

    @Test
    void cashUpVariance_matchesFixture() throws Exception {
        run("cash-up-variance", RANGE).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.category").value("DAILY_STATEMENTS"))
                .andExpect(jsonPath("$.columns[10].type").value("VARIANCE"))
                .andExpect(jsonPath("$.columns[14].type").value("STATUS"))
                .andExpect(jsonPath("$.columns[14].statusTones.OVERDUE").value("negative"))
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s1, (int) fixture.s2, (int) fixture.s3)))
                .andExpect(jsonPath("$.rows[*].tradingDay", contains("2026-10-05", "2026-10-05", "2026-10-06")))
                .andExpect(jsonPath("$.rows[*].openingFloat", contains(200.0, 300.0, 100.0)))
                .andExpect(jsonPath("$.rows[*].cashSales", contains(290.0, 70.0, 40.0)))
                .andExpect(jsonPath("$.rows[*].cashRefunds", contains(50.0, 100.0, 0.0)))
                .andExpect(jsonPath("$.rows[*].expectedCash", contains(440.0, 270.0, 140.0)))
                .andExpect(jsonPath("$.rows[*].status", contains("CLOSED", "CLOSED", "OPEN")))
                .andExpect(jsonPath("$.rows[0].countedCash").value(440.00))
                .andExpect(jsonPath("$.rows[0].variance").value(0.00))
                .andExpect(jsonPath("$.rows[0].openedAt").value("2026-10-05T05:00:00Z"))
                .andExpect(jsonPath("$.rows[0].closedAt").value("2026-10-05T09:00:00Z"))
                .andExpect(jsonPath("$.rows[1].countedCash").value(260.00))
                .andExpect(jsonPath("$.rows[1].variance").value(-10.00))
                .andExpect(jsonPath("$.rows[1].reason").value("Cash counting error"))
                .andExpect(jsonPath("$.rows[1].authorisedBy").value("Canteen Admin"))
                .andExpect(jsonPath("$.rows[2].countedCash").value(nullValue()))
                .andExpect(jsonPath("$.rows[2].variance").value(nullValue()))
                .andExpect(jsonPath("$.rows[2].closedAt").value(nullValue()))
                .andExpect(jsonPath("$.rows[2].closedBy").value(nullValue()))
                .andExpect(jsonPath("$.totals.openingFloat").value(600.00))
                .andExpect(jsonPath("$.totals.cashSales").value(400.00))
                .andExpect(jsonPath("$.totals.cashRefunds").value(150.00))
                .andExpect(jsonPath("$.totals.expectedCash").value(850.00))
                .andExpect(jsonPath("$.totals.countedCash").value(700.00))
                .andExpect(jsonPath("$.totals.variance").value(-10.00))
                .andExpect(jsonPath("$.totals.reason").value("1 with a variance"))
                .andExpect(jsonPath("$.totals.owner").value("3 shifts"))
                .andExpect(jsonPath("$.checks[*].key", contains("expected-arithmetic", "variance-arithmetic", "matches-tender", "open-shifts")))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS", "PASS", "WARN")))
                .andExpect(jsonPath("$.checks[*].unit", contains("COUNT", "COUNT", "MONEY", "COUNT")))
                .andExpect(jsonPath("$.checks[2].expected").value(250.00))
                .andExpect(jsonPath("$.checks[2].actual").value(250.00))
                .andExpect(jsonPath("$.checks[3].actual").value(1))
                .andExpect(jsonPath("$.checks[3].detail").value("1 shift is still open, so expected cash is live."));
    }

    @Test
    void cashUpVariance_filters() throws Exception {
        run("cash-up-variance", RANGE + "&varianceOnly=true")
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s2)))
                .andExpect(jsonPath("$.rows[0].variance").value(-10.00))
                // Reconciliation checks ignore filters: they always cover every shift in the range.
                .andExpect(jsonPath("$.checks[2].status").value("PASS"));
        run("cash-up-variance", RANGE + "&status=OPEN")
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s3)))
                .andExpect(jsonPath("$.totals.countedCash").value(nullValue()));
        run("cash-up-variance", RANGE + "&ownerId=" + AuthTestHelper.cashierAId(mockMvc))
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) fixture.s1)));
    }

    @Test
    void cashUpVariance_shiftOpenFromAnEarlierDay_isOverdue() throws Exception {
        clock.setInstant(TUESDAY.plusDays(1).atTime(9, 0).atZone(ClockConfig.BUSINESS_ZONE).toInstant());
        run("cash-up-variance", RANGE).andExpect(jsonPath("$.rows[2].status").value("OVERDUE"));
    }

    @Test
    void tamperedOrderTotal_failsNetAgreesWithOrders() throws Exception {
        jdbc.update("UPDATE orders SET total = 95.00 WHERE id = ?", fixture.o1);

        run("sales-summary", RANGE + "&groupBy=DAY")
                .andExpect(jsonPath("$.checks[0].status").value("FAIL"))
                .andExpect(jsonPath("$.checks[0].expected").value(490.00))
                .andExpect(jsonPath("$.checks[0].actual").value(495.00))
                .andExpect(jsonPath("$.checks[0].difference").value(5.00));
    }

    @Test
    void overnightShift_orderAfterMidnight_staysOnTheShiftsTradingDay() throws Exception {
        ReportFixture.clear(jdbc);
        var cashier = AuthTestHelper.cashierAId(mockMvc);
        var wednesday = TUESDAY.plusDays(1);
        var f = ReportFixture.empty(jdbc);

        // Opened Wednesday 23:00, order Thursday 00:30, closed Thursday 02:00.
        var shift = f.closedShift(cashier, "100.00", ReportFixture.utc(wednesday, 23, 0), ReportFixture.utc(wednesday.plusDays(1), 2, 0),
                "150.00", "150.00", "0.00", null, null, cashier, "50.00", "0.00");
        var order = f.orderAt(shift, cashier, wednesday.plusDays(1), 1, ReportFixture.utc(wednesday.plusDays(1), 0, 30),
                "50.00", "50.00", "COLLECTED");
        f.payment(order, "CASH", "50.00");

        var wednesdayOnly = "from=" + wednesday + "&to=" + wednesday;
        run("sales-summary", wednesdayOnly + "&groupBy=DAY")
                .andExpect(jsonPath("$.rows[*].period", contains(wednesday.toString())))
                .andExpect(jsonPath("$.rows[0].grossSales").value(50.00));
        run("sales-summary", wednesdayOnly + "&groupBy=HOUR")
                .andExpect(jsonPath("$.rows[*].period", contains("00:00")));
        run("sales-summary", "from=" + wednesday.plusDays(1) + "&to=" + wednesday.plusDays(1))
                .andExpect(jsonPath("$.rows").isEmpty());
        run("cash-up-variance", wednesdayOnly)
                .andExpect(jsonPath("$.rows[*].shiftId", contains((int) shift)))
                .andExpect(jsonPath("$.rows[0].tradingDay").value(wednesday.toString()))
                .andExpect(jsonPath("$.rows[0].cashSales").value(50.00))
                .andExpect(jsonPath("$.checks[2].status").value("PASS"));
        run("tender-summary", wednesdayOnly)
                .andExpect(jsonPath("$.totals.netCashMovement").value(50.00))
                .andExpect(jsonPath("$.checks[*].status", contains("PASS", "PASS")));
    }

    @Test
    void legacyShift_inCashUp_excludedFromCashChecksWithWarn() throws Exception {
        jdbc.update("UPDATE shifts SET cash_sales_at_close = NULL, cash_refunds_at_close = NULL WHERE id = ?", fixture.s1);

        run("cash-up-variance", RANGE)
                .andExpect(jsonPath("$.rows[0].shiftId").value(fixture.s1))
                .andExpect(jsonPath("$.rows[0].cashSales").value(290.00))
                .andExpect(jsonPath("$.rows[0].expectedCash").value(440.00))
                .andExpect(jsonPath("$.checks[0].status").value("WARN"))
                .andExpect(jsonPath("$.checks[2].status").value("WARN"))
                .andExpect(jsonPath("$.checks[2].difference").value(0.00));
        run("tender-summary", RANGE)
                .andExpect(jsonPath("$.checks[1].key").value("cash-matches-cashups"))
                .andExpect(jsonPath("$.checks[1].status").value("WARN"))
                .andExpect(jsonPath("$.checks[1].expected").value(10.00))
                .andExpect(jsonPath("$.checks[1].actual").value(10.00))
                .andExpect(jsonPath("$.checks[1].detail").value("1 legacy shift excluded: closed before the cash breakdown was stored."));
    }

    @Test
    void params_defaultsAndValidation() throws Exception {
        // Clock is Tuesday 2026-10-06: the default is the 7 days ending today.
        run("sales-summary", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.params.from").value("2026-09-30"))
                .andExpect(jsonPath("$.meta.params.to").value("2026-10-06"))
                .andExpect(jsonPath("$.meta.params.groupBy").value("DAY"))
                .andExpect(jsonPath("$.totals.orders").value(9));

        run("sales-summary", "from=2026-10-06&to=2026-10-05").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.from").value("From must be on or before To."));
        // 2025-10-05 → 2026-10-05 is exactly 366 days inclusive (allowed); one more day is not.
        run("sales-summary", "from=2025-10-05&to=2026-10-05").andExpect(status().isOk());
        run("sales-summary", "from=2025-10-04&to=2026-10-05").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.to").value("Range can't exceed 366 days."));
        run("sales-summary", RANGE + "&groupBy=YEAR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.groupBy").value("Group by must be one of DAY, WEEK, MONTH, HOUR."));
        run("tender-summary", RANGE + "&groupBy=HOUR").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.groupBy").exists());
        run("sales-summary", "from=05/10/2026").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.from").exists());
        run("cash-up-variance", RANGE + "&varianceOnly=maybe").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.varianceOnly").exists());
        run("sales-summary", RANGE + "&somethingElse=ignored").andExpect(status().isOk());
        run("no-such-report", RANGE).andExpect(status().isNotFound());
    }

    @Test
    void csvExport_matchesTheEnvelope() throws Exception {
        var response = export("sales-summary", RANGE + "&groupBy=DAY&format=csv").andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"sales-summary_2026-10-05_2026-10-06.csv\""))
                .andReturn().getResponse();
        assertThat(response.getContentType()).startsWith("text/csv");

        var bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        var lines = new String(bytes, StandardCharsets.UTF_8).substring(1).split("\r\n");
        assertThat(lines).containsExactly(
                "Date,Orders,Voided/refunded orders,Net orders,Gross sales,Voids,Refunds,Discounts,Extras removed,Net sales,Average order value",
                "2026-10-05,7,2,5,570.00,50.00,90.00,10.00,0.00,420.00,84.00",
                "2026-10-06,2,0,2,90.00,0.00,0.00,0.00,15.00,75.00,37.50");
    }

    @Test
    void xlsxExport_hasTitleBlockTypedCellsTotalsAndChecksSheet() throws Exception {
        var response = export("cash-up-variance", RANGE + "&format=xlsx").andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"cash-up-variance_2026-10-05_2026-10-06.xlsx\""))
                .andReturn().getResponse();

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheet = workbook.getSheet("Report");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Cash-up & Variance");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Amounts include VAT");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("Trading day");
            // Data starts on row 6; S2's expected cash (col 8) and variance (col 10) are numeric cells.
            assertThat(sheet.getRow(7).getCell(8).getNumericCellValue()).isEqualTo(270.0);
            assertThat(sheet.getRow(7).getCell(10).getNumericCellValue()).isEqualTo(-10.0);
            assertThat(sheet.getRow(6).getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(MONDAY);
            // Totals row follows the three data rows.
            assertThat(sheet.getRow(9).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getRow(9).getCell(8).getNumericCellValue()).isEqualTo(850.0);

            var checks = workbook.getSheet("Checks");
            assertThat(checks).isNotNull();
            assertThat(checks.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Check");
            assertThat(checks.getRow(4).getCell(1).getStringCellValue()).isEqualTo("WARN");
        }
    }

    @Test
    void export_rejectsUnknownFormat() throws Exception {
        export("sales-summary", RANGE + "&format=pdf").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.format").value("Format must be csv or xlsx."));
    }
}
