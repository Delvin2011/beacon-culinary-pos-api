package com.beaconculinary.api.settings;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.auth.RecentPasswordCheck;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.devices.DeviceException;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.oversight.PageDto;
import com.beaconculinary.api.settings.SiteSettingsDtos.HistoryEntry;
import com.beaconculinary.api.settings.SiteSettingsDtos.LastChange;
import com.beaconculinary.api.settings.SiteSettingsDtos.LimitsView;
import com.beaconculinary.api.settings.SiteSettingsDtos.SettingView;
import com.beaconculinary.api.settings.SiteSettingsDtos.SettingsView;
import com.beaconculinary.api.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * T0-4a: the admin side of site settings (contract section 3). Every write takes an application
 * lock first, reads the saved values afresh (not the cache), validates the result as a whole, and
 * then writes the values and their history in the same transaction. The response is built from that
 * fresh state, so it already shows the change; the cache is dropped once the transaction commits.
 *
 * <p>Order of checks: 400 (validation, every bad key at once) before 403 (REAUTH_REQUIRED, only when
 * a sensitive setting would actually change).
 */
@Service
public class SiteSettingsAdminService {
    static final int MAX_PAGE_SIZE = 200;
    private static final String NOT_ENTITLED = "OFFLINE_NOT_ENTITLED";

    private final SiteSettings siteSettings;
    private final SiteSettingsStore store;
    private final FirstTradingDayRule firstTradingDayRule;
    private final OfflineProperties offlineProperties;
    private final RecentPasswordCheck recentPasswordCheck;
    private final AuthService authService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SiteSettingsAdminService(SiteSettings siteSettings, SiteSettingsStore store, FirstTradingDayRule firstTradingDayRule,
                                    OfflineProperties offlineProperties, RecentPasswordCheck recentPasswordCheck,
                                    AuthService authService, ApplicationEventPublisher events, Clock clock) {
        this.siteSettings = siteSettings;
        this.store = store;
        this.firstTradingDayRule = firstTradingDayRule;
        this.offlineProperties = offlineProperties;
        this.recentPasswordCheck = recentPasswordCheck;
        this.authService = authService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        return view(siteSettings.load());
    }

    /** PUT /admin/settings: a partial update, all or nothing. */
    @Transactional
    public SettingsView update(Map<String, JsonNode> body) {
        store.lockForWrite();
        var saved = siteSettings.load();
        var current = effective(saved);
        var defaults = siteSettings.defaults();

        var errors = new TreeMap<String, String>();
        var sent = new EnumMap<SiteSetting, Object>(SiteSetting.class);
        body.forEach((key, node) -> {
            var setting = SiteSetting.byKey(key).orElse(null);
            if (setting == null) {
                errors.put(key, "Unknown setting.");
                return;
            }
            try {
                var value = node == null || node.isNull() ? null : setting.type().fromJson(node);
                var problem = SiteSettingRules.checkLimits(setting, value, defaults.grantMaxHours());
                if (problem != null) {
                    errors.put(key, problem);
                } else {
                    sent.put(setting, SiteSettingRules.normalize(setting, value));
                }
            } catch (SettingType.BadValueException e) {
                errors.put(key, e.getMessage());
            }
        });

        var changed = new EnumMap<SiteSetting, Object>(SiteSetting.class);
        sent.forEach((setting, value) -> {
            if (!SiteSettingRules.sameValue(current.get(setting), value)) {
                changed.put(setting, value);
            }
        });
        if (errors.isEmpty()) {
            var result = new EnumMap<>(current);
            result.putAll(changed);
            SiteSettingRules.crossRules(result).forEach((setting, message) -> errors.putIfAbsent(setting.key(), message));
            if (changed.containsKey(SiteSetting.FIRST_TRADING_DAY)) {
                var problem = firstTradingDayRule.check((LocalDate) changed.get(SiteSetting.FIRST_TRADING_DAY));
                if (problem != null) {
                    errors.put(SiteSetting.FIRST_TRADING_DAY.key(), problem);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new SiteSettingsValidationException(errors);
        }
        if (changed.isEmpty()) {
            return view(saved);
        }

        var user = actingUser(changed.keySet());
        var now = UtcTimestamps.nowUtc(clock);
        changed.forEach((setting, value) -> {
            var text = text(setting, value);
            store.upsert(setting.key(), text, now, user.getId(), user.getName());
            store.insertChange(setting.key(), "SET", text(setting, current.get(setting)), !saved.containsKey(setting),
                    text, false, now, user.getId(), user.getName());
            saved.put(setting, value);
        });
        events.publishEvent(new SiteSettingsChangedEvent(Set.copyOf(changed.keySet())));
        return view(saved);
    }

    /** POST /admin/settings/{key}/reset: back to the default. */
    @Transactional
    public SettingsView reset(String key) {
        var setting = SiteSetting.byKey(key).orElseThrow(() -> new UnknownSettingException(key));
        store.lockForWrite();
        var saved = siteSettings.load();
        var stored = store.findAll().stream().anyMatch(row -> row.key().equals(key));
        if (!stored) {
            return view(saved);
        }
        var current = effective(saved);
        var defaultValue = siteSettings.defaults().get(setting);
        var result = new EnumMap<>(current);
        result.put(setting, defaultValue);
        var errors = new TreeMap<String, String>();
        SiteSettingRules.crossRules(result).forEach((broken, message) -> errors.put(broken.key(), message));
        if (setting == SiteSetting.FIRST_TRADING_DAY && !SiteSettingRules.sameValue(current.get(setting), defaultValue)) {
            var problem = firstTradingDayRule.check((LocalDate) defaultValue);
            if (problem != null) {
                errors.put(setting.key(), problem);
            }
        }
        if (!errors.isEmpty()) {
            throw new SiteSettingsValidationException(errors);
        }

        var user = actingUser(EnumSet.of(setting));
        var now = UtcTimestamps.nowUtc(clock);
        store.delete(key);
        store.insertChange(key, "RESET", text(setting, current.get(setting)), !saved.containsKey(setting),
                text(setting, defaultValue), true, now, user.getId(), user.getName());
        saved.remove(setting);
        events.publishEvent(new SiteSettingsChangedEvent(Set.of(setting)));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public PageDto<HistoryEntry> history(String key, int page, int size) {
        var safePage = Math.max(page, 0);
        var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var filter = key == null || key.isBlank() ? null : key.trim();
        var total = store.countHistory(filter);
        var content = store.history(filter, safePage, safeSize).stream().map(SiteSettingsAdminService::entry).toList();
        return new PageDto<>(content, safePage, safeSize, total, (int) ((total + safeSize - 1) / safeSize));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The signed-in admin; when a sensitive setting changes, only after a recent password sign-in. */
    private User actingUser(Set<SiteSetting> changing) {
        var sensitive = changing.stream().filter(SiteSetting::sensitive).map(SiteSetting::key).sorted().toList();
        if (sensitive.isEmpty()) {
            return authService.getCurrentUser();
        }
        try {
            return recentPasswordCheck.require("change " + String.join(", ", sensitive));
        } catch (DeviceException e) {
            throw new DeviceException(HttpStatus.FORBIDDEN, e.getCode(), e.getMessage(), Map.of("keys", sensitive));
        }
    }

    private Map<SiteSetting, Object> effective(Map<SiteSetting, Object> saved) {
        var values = siteSettings.defaults().all();
        values.putAll(saved);
        return values;
    }

    private SettingsView view(Map<SiteSetting, Object> saved) {
        var defaults = siteSettings.defaults();
        var lastChanges = store.lastChanges();
        var entitled = offlineProperties.isEnabled();
        var settings = new ArrayList<SettingView>();
        for (var setting : SiteSetting.values()) {
            var type = setting.type();
            var isDefault = !saved.containsKey(setting);
            var value = isDefault ? defaults.get(setting) : saved.get(setting);
            var inactive = setting.group() == SiteSetting.Group.OFFLINE && !entitled;
            var change = lastChanges.get(setting.key());
            settings.add(new SettingView(setting.key(), type.toJson(value), type.toJson(defaults.get(setting)), isDefault, type,
                    limits(setting, defaults.grantMaxHours()), setting.group(), setting.description(), setting.sensitive(),
                    !inactive, inactive ? NOT_ENTITLED : null,
                    change == null ? null : new LastChange(change.changedByName(), UtcTimestamps.toInstant(change.changedAt()),
                            change.action())));
        }
        return new SettingsView(entitled, settings);
    }

    private static LimitsView limits(SiteSetting setting, int grantMaxHours) {
        var limits = SiteSettingRules.limits(setting, grantMaxHours);
        var numeric = setting.type() == SettingType.DECIMAL || setting.type() == SettingType.INTEGER;
        return numeric ? new LimitsView(limits.min(), limits.max(), limits.scale(), limits.nullable())
                : new LimitsView(null, null, null, limits.nullable());
    }

    private static String text(SiteSetting setting, Object value) {
        return value == null ? null : setting.type().toText(value);
    }

    private static HistoryEntry entry(SiteSettingsStore.Change change) {
        var setting = SiteSetting.byKey(change.key()).orElse(null);
        return new HistoryEntry(change.id(), change.key(), change.action(), json(setting, change.oldValue()), change.oldIsDefault(),
                json(setting, change.newValue()), change.newIsDefault(), UtcTimestamps.toInstant(change.changedAt()),
                change.changedById(), change.changedByName());
    }

    /** A stored text as its API value; the text itself when the key or its form is no longer known. */
    private static Object json(SiteSetting setting, String text) {
        if (text == null || setting == null) {
            return text;
        }
        try {
            return setting.type().toJson(setting.type().fromText(text));
        } catch (SettingType.BadValueException e) {
            return text;
        }
    }
}
