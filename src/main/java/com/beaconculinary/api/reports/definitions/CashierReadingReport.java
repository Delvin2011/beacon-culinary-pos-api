package com.beaconculinary.api.reports.definitions;

import com.beaconculinary.api.common.ClockConfig;
import com.beaconculinary.api.reports.EndOfDayFacts;
import com.beaconculinary.api.reports.EndOfDayStatements;
import com.beaconculinary.api.reports.ParamOption;
import com.beaconculinary.api.reports.ReportCategory;
import com.beaconculinary.api.reports.ReportDefinition;
import com.beaconculinary.api.reports.ReportParamSpec;
import com.beaconculinary.api.reports.ReportParams;
import com.beaconculinary.api.reports.ReportResult;
import com.beaconculinary.api.reports.ReportTemplate;
import com.beaconculinary.api.users.Role;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reports R3 §5: one shift (the drawer, not a person). Also served to the till at
 * GET /shifts/{id}/reading for the shift's owner. */
@Component
@AllArgsConstructor
public class CashierReadingReport implements ReportDefinition {
    private static final int OPTION_DAYS = 14;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final EndOfDayStatements statements;
    private final EndOfDayFacts endOfDayFacts;
    private final Clock clock;

    @Override
    public String key() {
        return "cashier-reading";
    }

    @Override
    public String title() {
        return "Cashier Reading";
    }

    @Override
    public ReportCategory category() {
        return ReportCategory.DAILY_STATEMENTS;
    }

    @Override
    public String description() {
        return "One shift's takings, cash-up, adjustments and sign-off, for the cashier and manager to sign.";
    }

    @Override
    public ReportTemplate template() {
        return ReportTemplate.STATEMENT;
    }

    @Override
    public Set<Role> allowedRoles() {
        return Set.of(Role.ADMIN);
    }

    /** Shifts from the last 14 trading days, newest first: "Mon 5 Oct · Sipho N. · 11:05–15:00". */
    @Override
    public List<ReportParamSpec> params() {
        var since = LocalDate.ofInstant(clock.instant(), ClockConfig.BUSINESS_ZONE).minusDays(OPTION_DAYS - 1);
        var options = endOfDayFacts.shiftsSince(since).stream()
                .map(shift -> ParamOption.of(shift.shiftId(), label(shift)))
                .toList();
        return List.of(ReportParamSpec.requiredId("shiftId", "Shift", options));
    }

    private static String label(EndOfDayFacts.ShiftOption shift) {
        var opened = shift.openedAt().atZone(ClockConfig.BUSINESS_ZONE);
        var closed = shift.closedAt() == null ? "open" : TIME.format(shift.closedAt().atZone(ClockConfig.BUSINESS_ZONE));
        return DAY.format(opened) + " · " + shift.ownerName() + " · " + TIME.format(opened) + "–" + closed;
    }

    @Override
    public int definitionVersion() {
        return 1;
    }

    @Override
    public List<String> notes() {
        return List.of(
                "A cashier reading covers one shift (the cash drawer), whoever rang the sales. Sales rung by someone "
                        + "else on this shift are listed separately.",
                "Takings are the orders sold in this shift. Reductions after payment are voids, refunds and extras "
                        + "removed on those orders, whenever they were processed.",
                "Cash refunds processed in this shift come out of this drawer, even for orders sold in an earlier shift.",
                "An open shift's reading is interim: expected cash is live, and counted cash and variance are empty.",
                "Complimentary meals are given away with manager approval and no payment. They are not part of sales.");
    }

    @Override
    public ReportResult run(ReportParams params) {
        return statements.cashierReading(params.id("shiftId"));
    }
}
