package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFacts;
import com.beaconculinary.api.dashboard.DashboardFacts.AccountCharges;
import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** The top 3 accounts by charges in the range (R2's account movements: ACCOUNT payments by day
 * of sale), the total charged and the number of orders charged. */
@Component
@AllArgsConstructor
public class AccountCustomersSection implements SectionHandler {
    private static final int TOP = 3;

    private final DashboardFacts dashboardFacts;
    private final TradingCalendar calendar;

    @Override
    public DashboardSection section() {
        return DashboardSection.ACCOUNT_CUSTOMERS;
    }

    @Override
    public Result compute(Request request) {
        var range = calendar.span(request.range());
        var charges = dashboardFacts.accountCharges(range);
        var total = charges.stream().map(AccountCharges::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        var accounts = new ArrayList<Map<String, Object>>();
        charges.stream()
                .sorted(Comparator.comparing(AccountCharges::amount).reversed()
                        .thenComparing(AccountCharges::name, String.CASE_INSENSITIVE_ORDER))
                .limit(TOP)
                .forEach(account -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("accountId", account.accountId());
                    row.put("name", account.name());
                    row.put("amount", DashboardFormat.money(account.amount()));
                    accounts.add(row);
                });

        var data = new LinkedHashMap<String, Object>();
        data.put("accounts", accounts);
        data.put("total", DashboardFormat.money(total));
        data.put("orders", dashboardFacts.accountChargeOrders(range));
        return new Result(data, Links.report("account-balances", range));
    }
}
