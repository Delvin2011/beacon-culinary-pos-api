package com.beaconculinary.api.settings;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * T0-4a: the registry of customer (site) settings. Each one has a key (its configuration property
 * name), a type, single-value limits, a group, a description, whether changing it needs a recent
 * password sign-in. Its default is the YAML / environment value ({@link SiteSettingDefaults}).
 * Cross-field rules are in {@link SiteSettingRules}.
 *
 * <p>Adding a setting: an entry here, its default in SiteSettingDefaults, a typed getter in
 * SiteSettings, and a row in the contract. No migration. See docs/2026-11-20-site-settings.md.
 */
public enum SiteSetting {
    VAT_STANDARD_RATE("vat.standard-rate", SettingType.DECIMAL, Group.VAT, true,
            "Standard VAT rate, as a fraction (0.15 = 15%). Prices include VAT.",
            Limits.decimal("0", "0.9999", 4, false)),
    FIRST_TRADING_DAY("reports.close-day.first-trading-day", SettingType.DATE, Group.END_OF_DAY, true,
            "Where Z numbering starts. Earlier days are outside the end-of-day process. Empty: every day is in it.",
            Limits.NULLABLE),
    DASHBOARD_VARIANCE_THRESHOLD("dashboard.variance.threshold", SettingType.DECIMAL, Group.DASHBOARD, false,
            "A cash variance of this much or more is listed under Attention.",
            Limits.decimal("0", "99999999.99", 2, true)),
    DASHBOARD_VOID_REFUND_WATCH_PERCENT("dashboard.voidRefund.watchPercent", SettingType.DECIMAL, Group.DASHBOARD, false,
            "Voids and refunds above this share of sales are marked WATCH.",
            Limits.decimal("0", "100", 2, false)),
    DASHBOARD_SLOW_MEAL_THRESHOLD_PERCENT("dashboard.slowMeal.thresholdPercent", SettingType.DECIMAL, Group.DASHBOARD, false,
            "A meal that has sold less than this share of its plan after the slow-meal time is marked slow.",
            Limits.decimal("0", "100", 2, false)),
    DASHBOARD_SLOW_MEAL_AFTER_TIME("dashboard.slowMeal.afterTime", SettingType.TIME, Group.DASHBOARD, false,
            "From this time of day, meals selling slowly are flagged.",
            Limits.NONE),
    DASHBOARD_OPENING_HOURS("dashboard.openingHours", SettingType.TIME_RANGE, Group.DASHBOARD, false,
            "Opening hours: the hourly charts cover them, and slow meals are flagged only until closing.",
            Limits.NONE),
    BOARD_DONE_DISPLAY_MINUTES("board.done-display-minutes", SettingType.INTEGER, Group.BOARD, false,
            "Minutes a DONE order stays on the public board (0 = drops off straight away).",
            Limits.integer(0, 1440)),
    TILLS_REQUIRE_REGISTERED("pos.tills.require-registered", SettingType.BOOLEAN, Group.TILLS, true,
            "Shift open, sales, adjustments and CASHIER/ADMIN PIN sign-in need a registered till.",
            Limits.NONE),
    SESSION_IDLE_LOCK_MINUTES("pos.session.idle-lock-minutes", SettingType.INTEGER, Group.SESSIONS, false,
            "The POS app locks the till after this many minutes without activity.",
            Limits.integer(1, 1440)),
    SESSION_IDLE_EXPIRY_MINUTES("pos.session.idle-expiry-minutes", SettingType.INTEGER, Group.SESSIONS, false,
            "A POS session with no activity for this long ends (server backstop). At least the idle lock time.",
            Limits.integer(1, 1440)),
    OFFLINE_MAX_OFFLINE_HOURS("pos.offline.max-offline-hours", SettingType.INTEGER, Group.OFFLINE, false,
            "The till stops selling offline after this many hours. At most the grant lifetime (platform).",
            Limits.integer(1, null)),
    OFFLINE_WARN_OFFLINE_HOURS("pos.offline.warn-offline-hours", SettingType.INTEGER, Group.OFFLINE, false,
            "The till warns from this many hours offline. Less than the maximum.",
            Limits.integer(1, null)),
    OFFLINE_ACCOUNTS_ENABLED("pos.offline.accounts-enabled", SettingType.BOOLEAN, Group.OFFLINE, true,
            "Whether account (credit) sales are allowed offline.",
            Limits.NONE),
    OFFLINE_ACCOUNT_CAP("pos.offline.account-cap", SettingType.DECIMAL, Group.OFFLINE, true,
            "Most that may be charged to one account, on one till, during one outage.",
            Limits.decimal("0", "99999999.99", 2, true));

    public enum Group { VAT, END_OF_DAY, DASHBOARD, BOARD, TILLS, SESSIONS, OFFLINE }

    /**
     * Single-value limits. min/max apply to numbers (inclusive; a null max on OFFLINE_MAX_OFFLINE_HOURS
     * is filled in from the platform's grant-max-hours). scale: most decimals allowed. fixedScale:
     * money, stored with exactly {@code scale} decimals.
     */
    public record Limits(BigDecimal min, BigDecimal max, Integer scale, boolean fixedScale, boolean nullable) {
        static final Limits NONE = new Limits(null, null, null, false, false);
        static final Limits NULLABLE = new Limits(null, null, null, false, true);

        static Limits decimal(String min, String max, int scale, boolean fixedScale) {
            return new Limits(new BigDecimal(min), new BigDecimal(max), scale, fixedScale, false);
        }

        static Limits integer(int min, Integer max) {
            return new Limits(BigDecimal.valueOf(min), max == null ? null : BigDecimal.valueOf(max), 0, false, false);
        }

        public Limits withMax(BigDecimal newMax) {
            return new Limits(min, newMax, scale, fixedScale, nullable);
        }
    }

    private static final Map<String, SiteSetting> BY_KEY =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(SiteSetting::key, Function.identity()));

    private final String key;
    private final SettingType type;
    private final Group group;
    private final boolean sensitive;
    private final String description;
    private final Limits limits;

    SiteSetting(String key, SettingType type, Group group, boolean sensitive, String description, Limits limits) {
        this.key = key;
        this.type = type;
        this.group = group;
        this.sensitive = sensitive;
        this.description = description;
        this.limits = limits;
    }

    public static Optional<SiteSetting> byKey(String key) {
        return Optional.ofNullable(key == null ? null : BY_KEY.get(key));
    }

    public String key() {
        return key;
    }

    public SettingType type() {
        return type;
    }

    public Group group() {
        return group;
    }

    public boolean sensitive() {
        return sensitive;
    }

    public String description() {
        return description;
    }

    /** The static limits; SiteSettingRules.limits(...) adds the platform-dependent max. */
    public Limits staticLimits() {
        return limits;
    }
}
