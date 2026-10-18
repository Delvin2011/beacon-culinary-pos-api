package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.accounts.AccountService;
import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.reports.AccountFacts;
import com.beaconculinary.api.reports.AccountFacts.AccountBalance;
import com.beaconculinary.api.reports.CheckStatus;
import com.beaconculinary.api.reports.ColumnTotal;
import com.beaconculinary.api.reports.ColumnType;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportCheck;
import com.beaconculinary.api.reports.ReportColumn;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportFacts;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportRange;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportValues;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Reports R2 §5. Opening, movements and closing balance for every account over a range. */
@Component
@AllArgsConstructor
public class AccountBalancesReport implements ReportDefinition {
    private static final List<String> MONEY_KEYS = List.of("openingBalance", "charges", "credits", "paymentsReceived", "closingBalance");

    private final AccountFacts accountFacts;
    private final ReportFacts facts;
    private final AccountService accountService;
    private final Clock clock;

    @Override
    public String key() {
        return "account-balances";
    }

    @Override
    public String title() {
        return "Account Balances";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.PAYMENTS_ACCOUNTS;
    }

    @Override
    public String description() {
        return "What each account owed at the start and end of the period, and what changed it.";
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(ReportParamSpec.from(), ReportParamSpec.to(),
                ReportParamSpec.choice("show", "Show", "ALL", "OWING", "MOVEMENT"),
                ReportParamSpec.choice("sortBy", "Sort by", "CLOSING_BALANCE", "NAME"),
                ReportParamSpec.choice("direction", "Direction", "DESC", "ASC"));
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "Charges are orders paid on account, on the trading day the order was sold.",
                "Credits are money put back on the account after a void, refund or removed extras, on the trading day "
                        + "it was processed.",
                "Payments received are settlements paid by the account holder, on the day they were recorded.",
                "Closing balance is what the account owes; a negative balance means the account is in credit.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        var range = params.range();
        var accounts = accountFacts.balances(range);

        var shown = accounts.stream().filter(switch (params.string("show")) {
            case "OWING" -> account -> account.closing().signum() > 0;
            case "MOVEMENT" -> account -> account.movements() > 0;
            default -> account -> true;
        }).sorted(order(params.string("sortBy"), params.string("direction"))).toList();

        var columns = List.of(
                ReportColumn.of("account", "Account", ColumnType.TEXT, ColumnTotal.LABEL),
                ReportColumn.of("openingBalance", "Opening balance", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("charges", "Charges", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("credits", "Credits", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("paymentsReceived", "Payments received", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("closingBalance", "Closing balance", ColumnType.MONEY, ColumnTotal.SUM),
                ReportColumn.of("accountOrders", "Account orders", ColumnType.INT, ColumnTotal.SUM),
                ReportColumn.of("lastPayment", "Last payment", ColumnType.DATETIME, ColumnTotal.NONE));

        var rows = new ArrayList<Map<String, Object>>();
        var totals = new LinkedHashMap<String, Object>();
        totals.put("account", "Total");
        MONEY_KEYS.forEach(key -> totals.put(key, ReportValues.money(BigDecimal.ZERO)));
        totals.put("accountOrders", 0L);
        for (var account : shown) {
            var row = new LinkedHashMap<String, Object>();
            row.put("accountId", account.accountId());
            row.put("account", account.name());
            row.put("openingBalance", ReportValues.money(account.opening()));
            row.put("charges", ReportValues.money(account.charges()));
            row.put("credits", ReportValues.money(account.credits()));
            row.put("paymentsReceived", ReportValues.money(account.payments()));
            row.put("closingBalance", ReportValues.money(account.closing()));
            row.put("accountOrders", account.accountOrders());
            row.put("lastPayment", account.lastPaymentUtc() == null ? null : UtcTimestamps.toInstant(account.lastPaymentUtc()));
            for (var key : MONEY_KEYS) {
                totals.put(key, ((BigDecimal) totals.get(key)).add((BigDecimal) row.get(key)));
            }
            totals.put("accountOrders", (Long) totals.get("accountOrders") + account.accountOrders());
            rows.add(row);
        }

        return new ReportResult(columns, rows, totals, checks(range, accounts));
    }

    /** Checks cover every account, whatever the show filter. */
    private List<ReportCheck> checks(ReportRange range, List<AccountBalance> accounts) {
        var charges = sum(accounts, AccountBalance::charges);
        var credits = sum(accounts, AccountBalance::credits);
        var tenderAccount = facts.collected(range, GroupBy.MONTH).stream()
                .map(ReportFacts.CollectedFacts::account).reduce(BigDecimal.ZERO, BigDecimal::add);
        var tenderCredits = facts.paidOut(range, GroupBy.MONTH).stream()
                .map(ReportFacts.PaidOutFacts::accountCredits).reduce(BigDecimal.ZERO, BigDecimal::add);

        var checks = new ArrayList<ReportCheck>();
        checks.add(ReportCheck.compare("charges-agree-with-tender", "Charges equal Tender Summary's Account collected",
                tenderAccount, charges, null));
        checks.add(ReportCheck.compare("credits-agree-with-tender", "Credits equal Tender Summary's Account credits",
                tenderCredits, credits, null));

        var failing = accounts.stream()
                .filter(account -> ReportValues.money(account.closing()).compareTo(ReportValues.money(account.closingByLedger())) != 0)
                .count();
        checks.add(ReportCheck.failures("balance-arithmetic",
                "Opening + charges − credits − payments equals each account's ledger balance at the end of the period",
                failing, failing == 0 ? null : failing + (failing == 1 ? " account doesn't" : " accounts don't") + " add up"));

        // The account screen shows today's balance, so this only compares when the period runs to today.
        var today = LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE);
        if (!range.to().isBefore(today)) {
            var screen = accounts.stream()
                    .map(account -> accountService.getBalance(account.accountId()).getOutstandingBalance())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            checks.add(ReportCheck.compare("matches-account-screen", "Closing balances equal the balances on the account screen",
                    screen, sum(accounts, AccountBalance::closing), null));
        }

        var inCredit = accounts.stream().filter(account -> account.closing().signum() < 0).count();
        checks.add(ReportCheck.count("credit-balances", "Accounts in credit", inCredit,
                inCredit == 0 ? CheckStatus.PASS : CheckStatus.WARN,
                inCredit == 0 ? null : inCredit + (inCredit == 1 ? " account is" : " accounts are") + " in credit"));
        return checks;
    }

    private static Comparator<AccountBalance> order(String sortBy, String direction) {
        Comparator<AccountBalance> byName = Comparator.comparing(AccountBalance::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<AccountBalance> primary = sortBy.equals("NAME") ? byName : Comparator.comparing(AccountBalance::closing);
        if (direction.equals("DESC")) {
            primary = primary.reversed();
        }
        return primary.thenComparing(byName).thenComparing(AccountBalance::accountId);
    }

    private static BigDecimal sum(List<AccountBalance> accounts, Function<AccountBalance, BigDecimal> field) {
        return accounts.stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
