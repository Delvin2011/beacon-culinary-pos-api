package com.beaconculinary.api.dashboard.sections;

import com.beaconculinary.api.dashboard.DashboardFormat;
import com.beaconculinary.api.dashboard.DashboardSection;
import com.beaconculinary.api.dashboard.SectionHandler;
import com.beaconculinary.api.dashboard.TradingCalendar;
import com.beaconculinary.api.reports.GroupBy;
import com.beaconculinary.api.reports.ReportFacts;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The share of money collected by payment method: Tender Summary's collected figures (by day of
 * sale). Shares of money, not of orders, because a split order has more than one method.
 */
@Component
@AllArgsConstructor
public class PaymentMixSection implements SectionHandler {
    private final ReportFacts facts;
    private final TradingCalendar calendar;

    @Override
    public DashboardSection section() {
        return DashboardSection.PAYMENT_MIX;
    }

    @Override
    public Result compute(Request request) {
        var range = calendar.span(request.range());
        var cash = BigDecimal.ZERO;
        var card = BigDecimal.ZERO;
        var account = BigDecimal.ZERO;
        for (var row : facts.collected(range, GroupBy.DAY)) {
            cash = cash.add(row.cash());
            card = card.add(row.card());
            account = account.add(row.account());
        }
        var amounts = List.of(cash, card, account);
        var shares = DashboardFormat.shares(amounts);
        var methods = new ArrayList<Map<String, Object>>();
        var names = List.of("CASH", "CARD", "ACCOUNT");
        for (int i = 0; i < names.size() && !shares.isEmpty(); i++) {
            var method = new LinkedHashMap<String, Object>();
            method.put("method", names.get(i));
            method.put("amount", DashboardFormat.money(amounts.get(i)));
            method.put("percent", shares.get(i));
            methods.add(method);
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("basis", "MONEY_COLLECTED");
        data.put("total", DashboardFormat.money(cash.add(card).add(account)));
        data.put("methods", methods);
        return new Result(data, Links.report("tender-summary", range, "groupBy", GroupBy.DAY.name()));
    }
}
