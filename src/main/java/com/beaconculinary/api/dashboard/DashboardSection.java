package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.dashboard.DashboardSettings.Feature;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every dashboard section, in display order: its permission, whether it takes the range, how long
 * it is cached (and how often the frontend refreshes it), and the stages it needs. A section
 * whose stage flag is off is left out of the layout and answers 404.
 */
public enum DashboardSection {
    ATTENTION("attention", "Needs attention", false, 30, DashboardPermission.DASHBOARD_VIEW),
    NOW("now", "Right now", false, 30, DashboardPermission.DASHBOARD_VIEW),
    TODAY("today", "Today", false, 60, DashboardPermission.REPORTS_SALES),
    SALES_TREND("sales-trend", "Net sales trend", true, 60, DashboardPermission.REPORTS_SALES),
    SALES_MIX("sales-mix", "Where sales come from", true, 60, DashboardPermission.REPORTS_SALES,
            Feature.REPORT_DEFINITIONS),
    BUSY_HOURS("busy-hours", "Busy hours", false, 60, DashboardPermission.DASHBOARD_VIEW),
    HEATMAP("heatmap", "When customers come", false, 300, DashboardPermission.REPORTS_SALES),
    TOP_SELLERS("top-sellers", "What sells", true, 60, DashboardPermission.DASHBOARD_VIEW, Feature.REPORT_DEFINITIONS),
    SELL_THROUGH("sell-through", "Planned meals today", false, 60, DashboardPermission.DASHBOARD_VIEW,
            Feature.REPORT_DEFINITIONS),
    LIGHT_MEAL_STOCK("light-meal-stock", "Light meals: kitchen stock", false, 60, DashboardPermission.STOCK_VIEW,
            Feature.LIGHT_MEAL_STOCK),
    PAYMENT_MIX("payment-mix", "How customers pay", true, 60, DashboardPermission.REPORTS_CASH),
    BASKET("basket", "The basket", true, 60, DashboardPermission.REPORTS_SALES, Feature.REPORT_DEFINITIONS),
    WAITING("waiting", "Waiting", false, 60, DashboardPermission.DASHBOARD_VIEW),
    ACCOUNT_CUSTOMERS("account-customers", "Account customers", true, 60, DashboardPermission.REPORTS_ACCOUNTS,
            Feature.REPORT_DEFINITIONS, Feature.ACCOUNT_REPORTS);

    private final String key;
    private final String title;
    private final boolean usesRange;
    private final int refreshSeconds;
    private final DashboardPermission permission;
    private final List<Feature> needs;

    DashboardSection(String key, String title, boolean usesRange, int refreshSeconds, DashboardPermission permission,
                     Feature... needs) {
        this.key = key;
        this.title = title;
        this.usesRange = usesRange;
        this.refreshSeconds = refreshSeconds;
        this.permission = permission;
        this.needs = List.of(needs);
    }

    public static Optional<DashboardSection> byKey(String key) {
        return Arrays.stream(values()).filter(section -> section.key.equals(key)).findFirst();
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public boolean usesRange() {
        return usesRange;
    }

    public int refreshSeconds() {
        return refreshSeconds;
    }

    public DashboardPermission permission() {
        return permission;
    }

    public List<Feature> needs() {
        return needs;
    }
}
