package com.beaconculinary.api.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** T0-4a: response bodies of /admin/settings (contract section 3). */
public final class SiteSettingsDtos {
    private SiteSettingsDtos() {
    }

    /** GET /admin/settings, and the 200 of PUT and reset. */
    public record SettingsView(boolean offlineEntitled, List<SettingView> settings) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingView(String key,
                              @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
                              @JsonInclude(JsonInclude.Include.ALWAYS) Object defaultValue,
                              @JsonProperty("isDefault") boolean isDefault,
                              SettingType type, LimitsView limits, SiteSetting.Group group, String description,
                              boolean sensitive, boolean active, String inactiveReason,
                              @JsonInclude(JsonInclude.Include.ALWAYS) LastChange lastChange) {
    }

    /** min / max only for numbers; scale for numbers (0 for whole numbers). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LimitsView(BigDecimal min, BigDecimal max, Integer scale, boolean nullable) {
    }

    public record LastChange(String byName, Instant at, String action) {
    }

    /** One entry of GET /admin/settings/history. */
    public record HistoryEntry(long id, String key, String action,
                               @JsonInclude(JsonInclude.Include.ALWAYS) Object oldValue, boolean oldIsDefault,
                               @JsonInclude(JsonInclude.Include.ALWAYS) Object newValue, boolean newIsDefault,
                               Instant changedAt, long changedById, String changedByName) {
    }
}
