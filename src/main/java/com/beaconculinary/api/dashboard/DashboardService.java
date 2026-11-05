package com.beaconculinary.api.dashboard;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.ClockConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The back-office dashboard: a layout plus one independent request per section, so a slow or
 * failing section never blanks the page. Each section is permission-checked, flag-checked and
 * cached on its own; the layout runs no section query.
 */
@Slf4j
@Service
public class DashboardService {
    private final Map<DashboardSection, SectionHandler> handlers = new EnumMap<>(DashboardSection.class);
    private final DashboardSettings settings;
    private final DashboardCache cache;
    private final AuthService authService;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    public DashboardService(List<SectionHandler> handlers, DashboardSettings settings, DashboardCache cache,
                            AuthService authService, Clock clock, PlatformTransactionManager transactionManager) {
        handlers.forEach(handler -> {
            if (this.handlers.put(handler.section(), handler) != null) {
                throw new IllegalStateException("Two handlers for dashboard section " + handler.section().key());
            }
        });
        for (var section : DashboardSection.values()) {
            if (!this.handlers.containsKey(section)) {
                throw new IllegalStateException("No handler for dashboard section " + section.key());
            }
        }
        this.settings = settings;
        this.cache = cache;
        this.authService = authService;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    public record LayoutSection(String key, String title, boolean usesRange, int refreshSeconds) {
    }

    public record Layout(Instant asOf, String timezone, List<LayoutSection> sections) {
    }

    public record SectionEnvelope(String key, Instant asOf, String range, int definitionVersion, Object data,
                                  SectionHandler.ReportLink reportLink) {
    }

    /** The sections the caller may see and whose stages are on, in display order. */
    public Layout layout() {
        var caller = caller();
        if (!caller.has(DashboardPermission.DASHBOARD_VIEW)) {
            throw new AccessDeniedException("Not allowed to view the dashboard.");
        }
        var sections = java.util.Arrays.stream(DashboardSection.values())
                .filter(this::switchedOn)
                .filter(section -> caller.has(section.permission()))
                .map(section -> new LayoutSection(section.key(), section.title(), section.usesRange(), section.refreshSeconds()))
                .toList();
        return new Layout(clock.instant(), ClockConfig.BUSINESS_ZONE.getId(), sections);
    }

    /** 404 unknown (or switched off), 403 not permitted, 400 bad range or parameter, 500 when
     * the section itself fails. */
    public SectionEnvelope section(String key, Map<String, String> rawParams) {
        var section = DashboardSection.byKey(key).filter(this::switchedOn)
                .orElseThrow(() -> new DashboardSectionNotFoundException(key));
        var caller = caller();
        if (!caller.has(DashboardPermission.DASHBOARD_VIEW) || !caller.has(section.permission())) {
            throw new AccessDeniedException("Not allowed to view dashboard section " + key + ".");
        }
        var range = DashboardRange.parse(rawParams.get("range"));
        var handler = handlers.get(section);

        var params = new TreeMap<String, String>();
        handler.extraParams().forEach(name -> {
            var value = rawParams.get(name);
            if (value != null) {
                params.put(name, value.trim());
            }
        });
        var cacheKey = section.key() + "|" + range.value() + "|values=" + caller.seesValues() + "|role=" + caller.role()
                + (handler.perUser() ? "|user=" + caller.userId() : "") + "|" + params;

        var request = new SectionHandler.Request(caller, range, params);
        var entry = cache.get(cacheKey, section.refreshSeconds(), () -> compute(section, handler, request));
        var result = (SectionHandler.Result) entry.value();
        return new SectionEnvelope(section.key(), entry.computedAt(), range.value(), handler.definitionVersion(),
                result.data(), result.link());
    }

    private SectionHandler.Result compute(DashboardSection section, SectionHandler handler, SectionHandler.Request request) {
        try {
            return readOnly.execute(status -> handler.compute(request));
        } catch (DashboardParamException | AccessDeniedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Dashboard section {} failed", section.key(), e);
            throw new DashboardSectionFailedException(section.key(), e);
        }
    }

    private boolean switchedOn(DashboardSection section) {
        return section.needs().stream().allMatch(settings::enabled);
    }

    private DashboardCaller caller() {
        var user = authService.getCurrentUser();
        return new DashboardCaller(user.getId(), user.getRole());
    }

    /** For the controller's error body. */
    static Map<String, Object> error(String key, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("key", key);
        body.put("error", message);
        return body;
    }
}
