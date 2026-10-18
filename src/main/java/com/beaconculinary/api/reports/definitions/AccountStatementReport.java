package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.reports.AccountFacts;
import com.beaconculinary.api.reports.AccountFacts.AccountBalance;
import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reports R2 §6. One account's movements in date order with a running balance. */
@Component
@AllArgsConstructor
public class AccountStatementReport implements ReportDefinition {
    private static final Map<String, String> TYPE_TONES = Map.of(
            "OPENING", "neutral", "CHARGE", "warning", "CREDIT", "positive", "PAYMENT", "positive");

    private final AccountFacts accountFacts;

    @Override
    public String key() {
        return "account-statement";
    }

    @Override
    public String title() {
        return "Account Statement";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.PAYMENTS_ACCOUNTS;
    }

    @Override
    public String description() {
        return "Every charge, credit and payment on one account, with a running balance.";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.requiredId("accountId", "Account", accountFacts.accountOptions()),
                ReportParamSpec.from(), ReportParamSpec.to());
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Every charge, credit and payment on one account in date order, with a running balance.",
                "Charges increase what is owed; credits and payments reduce it.",
                "A movement is in the period by the same day as on Account Balances: charges by the trading day the "
                        + "order was sold, credits by the trading day they were processed, payments by the day recorded.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        long accountId = params.id("accountId");

        var columns = List.of(
                ReportColumn.of("occurredAt", "Date/time", ColumnType.DATETIME, ColumnTotal.LABEL),
                new ReportColumn("type", "Type", ColumnType.STATUS, ColumnTotal.NONE, TYPE_TONES),
                ReportColumn.of("reference", "Reference", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("description", "Description", ColumnType.TEXT, ColumnTotal.NONE),
                ReportColumn.of("charge", "Charge", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("credit", "Credit", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("payment", "Payment", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("balance", "Balance", ColumnType.MONEY, ColumnTotal.DERIVED));

        var balance = ReportValues.money(accountFacts.openingBalance(accountId, range));
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(row(range.from().atStartOfDay(ClockConfig.BUSINESS_ZONE).toInstant(), "OPENING", null,
                "Opening balance", null, null, null, balance));

        var charges = BigDecimal.ZERO;
        var credits = BigDecimal.ZERO;
        var payments = BigDecimal.ZERO;
        for (var movement : accountFacts.movements(accountId, range)) {
            var amount = ReportValues.money(movement.amount());
            var occurredAt = UtcTimestamps.toInstant(movement.occurredAtUtc());
            switch (movement.type()) {
                case "CHARGE" -> {
                    balance = balance.add(amount);
                    charges = charges.add(amount);
                    rows.add(row(occurredAt, "CHARGE", movement.reference(), movement.description(), amount, null, null, balance));
                }
                case "CREDIT" -> {
                    balance = balance.subtract(amount);
                    credits = credits.add(amount);
                    rows.add(row(occurredAt, "CREDIT", movement.reference(), movement.description(), null, amount, null, balance));
                }
                default -> {
                    balance = balance.subtract(amount);
                    payments = payments.add(amount);
                    rows.add(row(occurredAt, "PAYMENT", movement.reference(), movement.description(), null, null, amount, balance));
                }
            }
        }

        var totals = new LinkedHashMap<String, Object>();
        totals.put("occurredAt", "Closing balance");
        totals.put("charge", charges);
        totals.put("credit", credits);
        totals.put("payment", payments);
        totals.put("balance", balance);

        var balancesClosing = accountFacts.balances(range).stream()
                .filter(account -> account.accountId() == accountId)
                .map(AccountBalance::closing)
                .findFirst()
                .orElse(BigDecimal.ZERO);
        var check = ReportCheck.compare("closing-agrees-with-balances-report",
                "Closing balance equals Account Balances' closing balance for this account", balancesClosing, balance, null);

        return new ReportResult(columns, rows, totals, List.of(check));
    }

    private static Map<String, Object> row(Object occurredAt, String type, String reference, String description,
                                           BigDecimal charge, BigDecimal credit, BigDecimal payment, BigDecimal balance) {
        var row = new LinkedHashMap<String, Object>();
        row.put("occurredAt", occurredAt);
        row.put("type", type);
        row.put("reference", reference);
        row.put("description", description);
        row.put("charge", charge);
        row.put("credit", credit);
        row.put("payment", payment);
        row.put("balance", balance);
        return row;
    }
}
