package com.beaconculinary.api.settings;

import com.beaconculinary.api.common.UtcTimestamps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T0-4a: the customer's settings, as every consumer reads them. A setting's value is the one saved
 * in site_settings, otherwise its configuration default ({@link SiteSettingDefaults}).
 *
 * <p>Cache: the saved values are read in one query and kept for {@link #CACHE_TTL} (30 s) on the
 * application clock (a clock that moves backwards also expires it). A save on this instance drops
 * the cache once committed ({@link #onChanged}); other instances see it within 30 s. A read that
 * started before the drop doesn't put its older values back (generation check).
 *
 * <p>A saved value that no longer parses, breaks its limits (e.g. grant-max-hours was lowered below
 * a saved max-offline-hours) or breaks a cross-field rule with the other values is ignored with an
 * ERROR log, and the default is used for it (for a cross-field rule: for every setting involved).
 */
@Component
public class SiteSettings {
    public static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(SiteSettings.class);

    private final SiteSettingsStore store;
    private final SiteSettingDefaults defaults;
    private final Clock clock;
    private volatile Snapshot snapshot;
    private final AtomicLong generation = new AtomicLong();

    /** The saved values in effect (only those not ignored), and when they were read. */
    private record Snapshot(Instant loadedAt, Map<SiteSetting, Object> saved) {
    }

    public SiteSettings(SiteSettingsStore store, SiteSettingDefaults defaults, Clock clock) {
        this.store = store;
        this.defaults = defaults;
        this.clock = clock;
    }

    // ---- Typed getters (one per setting) -----------------------------------------------------

    public BigDecimal vatStandardRate() {
        return (BigDecimal) value(SiteSetting.VAT_STANDARD_RATE);
    }

    /** null when empty: every day is in the end-of-day process. */
    public LocalDate firstTradingDay() {
        return (LocalDate) value(SiteSetting.FIRST_TRADING_DAY);
    }

    public BigDecimal dashboardVarianceThreshold() {
        return (BigDecimal) value(SiteSetting.DASHBOARD_VARIANCE_THRESHOLD);
    }

    public BigDecimal dashboardVoidRefundWatchPercent() {
        return (BigDecimal) value(SiteSetting.DASHBOARD_VOID_REFUND_WATCH_PERCENT);
    }

    public BigDecimal dashboardSlowMealThresholdPercent() {
        return (BigDecimal) value(SiteSetting.DASHBOARD_SLOW_MEAL_THRESHOLD_PERCENT);
    }

    public LocalTime dashboardSlowMealAfter() {
        return (LocalTime) value(SiteSetting.DASHBOARD_SLOW_MEAL_AFTER_TIME);
    }

    public TimeRange dashboardOpeningHours() {
        return (TimeRange) value(SiteSetting.DASHBOARD_OPENING_HOURS);
    }

    public int boardDoneDisplayMinutes() {
        return (Integer) value(SiteSetting.BOARD_DONE_DISPLAY_MINUTES);
    }

    public boolean tillsRequireRegistered() {
        return (Boolean) value(SiteSetting.TILLS_REQUIRE_REGISTERED);
    }

    public int sessionIdleLockMinutes() {
        return (Integer) value(SiteSetting.SESSION_IDLE_LOCK_MINUTES);
    }

    public int sessionIdleExpiryMinutes() {
        return (Integer) value(SiteSetting.SESSION_IDLE_EXPIRY_MINUTES);
    }

    public int offlineMaxOfflineHours() {
        return (Integer) value(SiteSetting.OFFLINE_MAX_OFFLINE_HOURS);
    }

    public int offlineWarnOfflineHours() {
        return (Integer) value(SiteSetting.OFFLINE_WARN_OFFLINE_HOURS);
    }

    public boolean offlineAccountsEnabled() {
        return (Boolean) value(SiteSetting.OFFLINE_ACCOUNTS_ENABLED);
    }

    public BigDecimal offlineAccountCap() {
        return (BigDecimal) value(SiteSetting.OFFLINE_ACCOUNT_CAP);
    }

    // ---- Generic access ------------------------------------------------------------------------

    /** The effective value: saved, else the default. */
    public Object value(SiteSetting setting) {
        var saved = current().saved();
        return saved.containsKey(setting) ? saved.get(setting) : defaults.get(setting);
    }

    /** Whether a saved value is in effect (false = the default). */
    public boolean isSaved(SiteSetting setting) {
        return current().saved().containsKey(setting);
    }

    /** Every effective value. */
    public Map<SiteSetting, Object> effective() {
        var values = defaults.all();
        values.putAll(current().saved());
        return values;
    }

    public SiteSettingDefaults defaults() {
        return defaults;
    }

    /**
     * The value a setting had at an instant, from the history (SiteSettingsStore.valueAt): the value
     * after the last change before it, else the value before the first change after it; the current
     * value when the setting has never changed. Not cached: used for offline sales' VAT at sync.
     */
    public Object valueAt(SiteSetting setting, Instant at) {
        var historic = store.valueAt(setting.key(), UtcTimestamps.toUtc(at));
        if (historic.isEmpty()) {
            return value(setting);
        }
        var text = historic.get().text();
        try {
            return text == null ? null : setting.type().fromText(text);
        } catch (SettingType.BadValueException e) {
            log.error("Site setting {} has a history value that can't be read ({}); the current value is used.", setting.key(), text);
            return value(setting);
        }
    }

    /** Drops the cache, so the next read loads the saved values again. */
    public void invalidate() {
        generation.incrementAndGet();
        snapshot = null;
    }

    /** After a save or reset commits on this instance. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onChanged(SiteSettingsChangedEvent event) {
        invalidate();
    }

    private Snapshot current() {
        var now = clock.instant();
        var cached = snapshot;
        if (cached != null && !now.isBefore(cached.loadedAt()) && now.isBefore(cached.loadedAt().plus(CACHE_TTL))) {
            return cached;
        }
        synchronized (this) {
            cached = snapshot;
            if (cached != null && !now.isBefore(cached.loadedAt()) && now.isBefore(cached.loadedAt().plus(CACHE_TTL))) {
                return cached;
            }
            var started = generation.get();
            var fresh = new Snapshot(now, Collections.unmodifiableMap(load()));
            if (generation.get() == started) {
                snapshot = fresh;
            }
            return fresh;
        }
    }

    /** The saved values that are in effect (see the class comment for what is ignored). */
    Map<SiteSetting, Object> load() {
        var saved = new EnumMap<SiteSetting, Object>(SiteSetting.class);
        for (var row : store.findAll()) {
            var setting = SiteSetting.byKey(row.key()).orElse(null);
            if (setting == null) {
                log.error("Site setting '{}' is saved but unknown; ignored.", row.key());
                continue;
            }
            Object value;
            try {
                value = row.value() == null ? null : setting.type().fromText(row.value());
            } catch (SettingType.BadValueException e) {
                log.error("Site setting {} has a saved value that can't be read ({}); the default is used.", setting.key(), e.getMessage());
                continue;
            }
            var problem = SiteSettingRules.checkLimits(setting, value, defaults.grantMaxHours());
            if (problem != null) {
                log.error("Site setting {} has a saved value outside its limits ({}); the default is used.", setting.key(), problem);
                continue;
            }
            saved.put(setting, value);
        }
        // Cross-field rules on the result; drop every saved value a broken rule involves. Defaults
        // meet the rules (checked at startup), so this ends within a few rounds.
        for (int round = 0; round < SiteSetting.values().length; round++) {
            var values = defaults.all();
            values.putAll(saved);
            var broken = SiteSettingRules.crossRules(values);
            if (broken.isEmpty()) {
                break;
            }
            for (var entry : broken.entrySet()) {
                if (saved.containsKey(entry.getKey())) {
                    saved.remove(entry.getKey());
                    log.error("Site setting {} breaks a rule with other settings ({}); the default is used.",
                            entry.getKey().key(), entry.getValue());
                }
            }
        }
        return saved;
    }
}
