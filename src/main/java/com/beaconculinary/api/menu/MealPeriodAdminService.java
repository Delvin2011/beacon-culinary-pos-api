package com.beaconculinary.api.menu;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.AdminMealPeriod;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.HistoryEntry;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.Values;
import com.beaconculinary.api.oversight.PageDto;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * T0-5a: create, edit, deactivate and activate meal periods (contract
 * docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md). ADMIN only (/admin/**).
 *
 * <p>Order of checks: 404, then ALL_DAY_PROTECTED, then the 400 field map, then the rules that need
 * other rows (unique active name: 400; overlap: 409 PERIOD_OVERLAP; in use: 409 PERIOD_IN_USE), which
 * run under an application lock so two admins can't break them together. Every change is recorded
 * in meal_period_changes; a request that changes nothing records nothing.
 */
@Service
public class MealPeriodAdminService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Comparator<MealPeriod> LIST_ORDER =
            Comparator.comparingInt(MealPeriod::getSortOrder).thenComparing(MealPeriod::getId);

    private final MealPeriodRepository repository;
    private final MealPeriodChangeStore store;
    private final AuthService authService;
    private final Clock clock;

    public MealPeriodAdminService(MealPeriodRepository repository, MealPeriodChangeStore store,
                                  AuthService authService, Clock clock) {
        this.repository = repository;
        this.store = store;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminMealPeriod> list() {
        var last = store.lastChanges();
        return repository.findAll().stream().sorted(LIST_ORDER)
                .map(period -> AdminMealPeriod.of(period, last.get(period.getId())))
                .toList();
    }

    /** A new timed period that accepts set meals, active at once; without sortOrder it goes last. */
    @Transactional
    public AdminMealPeriod create(JsonNode body) {
        var fields = MealPeriodRules.read(body, true);
        store.lockForWrite();
        var all = repository.findAll();
        requireNameFree(fields.name(), null, all);
        requireNoOverlap(fields.startTime(), fields.endTime(), null, all);

        var period = new MealPeriod();
        period.setName(fields.name());
        period.setStartTime(fields.startTime());
        period.setEndTime(fields.endTime());
        period.setAllDay(false);
        period.setAccepts(CatalogGroup.SET_MEALS);
        period.setActive(true);
        period.setSortOrder(fields.sortOrder() != null ? fields.sortOrder() : nextSortOrder(all));
        repository.saveAndFlush(period);
        record(period, "CREATE", null);
        return view(period);
    }

    /** Replaces name and times (sortOrder only when sent). All Day takes no times. */
    @Transactional
    public AdminMealPeriod update(long id, JsonNode body) {
        var period = find(id);
        if (period.isAllDay() && MealPeriodRules.hasTimes(body)) {
            throw allDayProtected("The All Day period has no times; it is always open.");
        }
        var fields = MealPeriodRules.read(body, !period.isAllDay());
        store.lockForWrite();
        var all = repository.findAll();
        var sortOrder = fields.sortOrder() != null ? fields.sortOrder() : period.getSortOrder();
        var before = Values.of(period);
        if (before.equals(new Values(fields.name(), fields.startTime(), fields.endTime(), sortOrder, period.isActive()))) {
            return view(period);
        }
        if (period.isActive()) {
            requireNameFree(fields.name(), period.getId(), all);
            if (!period.isAllDay()) {
                requireNoOverlap(fields.startTime(), fields.endTime(), period.getId(), all);
            }
        }
        period.setName(fields.name());
        period.setStartTime(fields.startTime());
        period.setEndTime(fields.endTime());
        period.setSortOrder(sortOrder);
        repository.saveAndFlush(period);
        record(period, "UPDATE", before);
        return view(period);
    }

    /** Refused for All Day, and while the period has options or extras dated today or later. */
    @Transactional
    public AdminMealPeriod deactivate(long id) {
        var period = find(id);
        if (period.isAllDay()) {
            throw allDayProtected("The All Day period can't be deactivated: light meals are offered under it every day.");
        }
        if (!period.isActive()) {
            return view(period);
        }
        store.lockForWrite();
        var inUse = store.inUseFrom(period.getId(), LocalDate.now(clock));
        if (inUse.any()) {
            var items = inUse.options() + inUse.extras();
            throw MealPeriodRuleException.conflict("PERIOD_IN_USE",
                    period.getName() + " has " + items + " planned item" + (items == 1 ? "" : "s") + " from "
                            + DAY.format(inUse.fromDate()) + ". Move or remove them first.",
                    details("options", inUse.options(), "extras", inUse.extras(), "fromDate", inUse.fromDate()));
        }
        var before = Values.of(period);
        period.setActive(false);
        repository.saveAndFlush(period);
        record(period, "DEACTIVATE", before);
        return view(period);
    }

    /** Checks the name and the overlap again, against the periods active now. */
    @Transactional
    public AdminMealPeriod activate(long id) {
        var period = find(id);
        if (period.isActive()) {
            return view(period);
        }
        store.lockForWrite();
        var all = repository.findAll();
        requireNameFree(period.getName(), period.getId(), all);
        if (!period.isAllDay()) {
            requireNoOverlap(period.getStartTime(), period.getEndTime(), period.getId(), all);
        }
        var before = Values.of(period);
        period.setActive(true);
        repository.saveAndFlush(period);
        record(period, "ACTIVATE", before);
        return view(period);
    }

    @Transactional(readOnly = true)
    public PageDto<HistoryEntry> history(Long periodId, int page, int size) {
        var safePage = Math.max(page, 0);
        var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var total = store.countHistory(periodId);
        return new PageDto<>(store.history(periodId, safePage, safeSize), safePage, safeSize, total,
                (int) ((total + safeSize - 1) / safeSize));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private MealPeriod find(long id) {
        return repository.findById(id).orElseThrow(() -> MealPeriodRuleException.notFound(id));
    }

    private static void requireNameFree(String name, Long self, List<MealPeriod> all) {
        all.stream()
                .filter(other -> other.isActive() && !Objects.equals(other.getId(), self))
                .filter(other -> other.getName().trim().equalsIgnoreCase(name.trim()))
                .findFirst()
                .ifPresent(other -> {
                    throw new MealPeriodValidationException(Map.of("name",
                            "Another active period is already called " + other.getName() + "."));
                });
    }

    private static void requireNoOverlap(LocalTime start, LocalTime end, Long self, List<MealPeriod> all) {
        var others = all.stream().filter(other -> !Objects.equals(other.getId(), self)).toList();
        MealPeriodRules.overlap(start, end, others).ifPresent(other -> {
            throw MealPeriodRuleException.conflict("PERIOD_OVERLAP",
                    "Overlaps " + other.getName() + " (" + HH_MM.format(other.getStartTime()) + "–"
                            + HH_MM.format(other.getEndTime()) + ").",
                    details("periodId", other.getId(), "name", other.getName(),
                            "startTime", other.getStartTime(), "endTime", other.getEndTime()));
        });
    }

    private static int nextSortOrder(List<MealPeriod> all) {
        var max = all.stream().mapToInt(MealPeriod::getSortOrder).max().orElse(0);
        return Math.min(max + 10, MealPeriodRules.MAX_SORT_ORDER);
    }

    private void record(MealPeriod period, String action, Values before) {
        var user = authService.getCurrentUser();
        store.insert(period.getId(), action, before, Values.of(period), UtcTimestamps.nowUtc(clock), user.getId(), user.getName());
    }

    private AdminMealPeriod view(MealPeriod period) {
        return AdminMealPeriod.of(period, store.lastChanges().get(period.getId()));
    }

    private static MealPeriodRuleException allDayProtected(String message) {
        return MealPeriodRuleException.conflict("ALL_DAY_PROTECTED", message, null);
    }

    private static Map<String, Object> details(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
