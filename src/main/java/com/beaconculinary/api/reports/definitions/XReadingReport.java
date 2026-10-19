package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.ParamType;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportTemplate;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Reports R3 §4: the trading day so far, every shift combined. Live and read-only; offers
 * Close Day once every shift is closed and signed off. */
@Component
@AllArgsConstructor
public class XReadingReport implements ReportDefinition {
    private final EndOfDayStatements statements;

    @Override
    public String key() {
        return "x-reading";
    }

    @Override
    public String title() {
        return "X Reading";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.DAILY_STATEMENTS;
    }

    @Override
    public String description() {
        return "The trading day so far: sales, tender, cash and every shift, before the day is closed.";
    }

    @Override
    public ReportTemplate template() {
        return ReportTemplate.STATEMENT;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    @Override
    public List<ReportParamSpec> params() {
        return List.of(new ReportParamSpec("tradingDay", "Trading day", ParamType.DATE, "today", null));
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return DAY_NOTES;
    }

    static final List<String> DAY_NOTES = List.of(
            "A trading day is the day a shift opened: every shift that opened that day is included, even if it closed after midnight.",
            "Sales, discounts, voids, refunds and net sales are the Sales Summary figures for the day.",
            "Tender collected is what was taken by payment method; paid out is cash handed back and account credits.",
            "Cashier readings vs X reading adds up each shift's takings by tender and compares them with the day's figures.",
            "Complimentary meals are given away with manager approval and no payment. They are shown separately and "
                    + "are not part of sales or net sales. Value is at list price.",
            "Close Day needs every shift closed and signed off. It produces a numbered Z Reading that can't be changed.");

    @Override
    public ReportResult run(ReportParams params) {
        return statements.dayReading(params.date("tradingDay"), null);
    }
}
