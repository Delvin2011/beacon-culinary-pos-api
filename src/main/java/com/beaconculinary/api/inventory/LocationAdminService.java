package com.beaconculinary.api.inventory;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.inventory.LocationAdminDtos.AdminLocation;
import com.beaconculinary.api.inventory.LocationAdminDtos.Assignments;
import com.beaconculinary.api.inventory.LocationAdminDtos.AssignmentsBody;
import com.beaconculinary.api.inventory.LocationAdminDtos.HistoryEntry;
import com.beaconculinary.api.inventory.LocationAdminDtos.LocationBody;
import com.beaconculinary.api.inventory.LocationAdminDtos.LocationRef;
import com.beaconculinary.api.inventory.LocationAdminDtos.StockSummary;
import com.beaconculinary.api.inventory.LocationAdminDtos.Values;
import com.beaconculinary.api.menu.Station;
import com.beaconculinary.api.oversight.PageDto;
import com.beaconculinary.api.settings.SiteSettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T0-5c: stock locations and their assignments for admins (contract
 * docs/2026-11-21-t0-5c-locations-api-contract.md). ADMIN only (/admin/**).
 *
 * <p>Order of checks: 404, then 400 LOCATION_INVALID (fields), then the rules that need other rows
 * (409 LOCATION_NAME_TAKEN, LOCATION_LIMIT_REACHED, LOCATION_DEACTIVATE_REFUSED; 400 on bad
 * assignments), which run under an application lock. Every change is recorded in
 * location_changes; a request that changes nothing records nothing.
 */
@Service
public class LocationAdminService {
    static final int MAX_NAME_LENGTH = 50;
    static final int MAX_SORT_ORDER = 9999;
    private static final int MAX_PAGE_SIZE = 200;

    private final LocationRepository repository;
    private final LocationAssignmentRepository assignmentRepository;
    private final LocationChangeStore store;
    private final StockLocations stockLocations;
    private final SiteSettings siteSettings;
    private final AuthService authService;
    private final Clock clock;

    public LocationAdminService(LocationRepository repository, LocationAssignmentRepository assignmentRepository,
                                LocationChangeStore store, StockLocations stockLocations, SiteSettings siteSettings,
                                AuthService authService, Clock clock) {
        this.repository = repository;
        this.assignmentRepository = assignmentRepository;
        this.store = store;
        this.stockLocations = stockLocations;
        this.siteSettings = siteSettings;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminLocation> list() {
        var summaries = store.stockSummaries();
        var roles = rolesByLocation();
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(location -> view(location, summaries, roles))
                .toList();
    }

    /** A new SECONDARY location, active at once; without sortOrder it goes last. */
    @Transactional
    public AdminLocation create(LocationBody body) {
        var fields = read(body, true);
        store.lockForWrite();
        var all = repository.findAll();
        requireNameFree(fields.name(), null, all);
        requireBelowLimit(all);

        var now = UtcTimestamps.nowUtc(clock);
        var user = authService.getCurrentUser();
        var location = new Location();
        location.setName(fields.name());
        location.setKind(LocationKind.SECONDARY);
        location.setActive(true);
        location.setSortOrder(fields.sortOrder() != null ? fields.sortOrder() : nextSortOrder(all));
        location.setCreatedAt(now);
        location.setCreatedBy(user.getId());
        location.setCreatedByName(user.getName());
        location.setUpdatedAt(now);
        location.setUpdatedBy(user.getId());
        location.setUpdatedByName(user.getName());
        repository.saveAndFlush(location);
        record(location, "CREATE", null, null, null, now);
        return view(location);
    }

    /** Renames and (when sent) reorders. The primary can be renamed too. */
    @Transactional
    public AdminLocation update(long id, LocationBody body) {
        var location = find(id);
        var fields = read(body, true);
        store.lockForWrite();
        var sortOrder = fields.sortOrder() != null ? fields.sortOrder() : location.getSortOrder();
        var before = Values.of(location);
        if (before.equals(new Values(fields.name(), sortOrder, location.isActive()))) {
            return view(location);
        }
        if (location.isActive()) {
            requireNameFree(fields.name(), location.getId(), repository.findAll());
        }
        location.setName(fields.name());
        location.setSortOrder(sortOrder);
        stamp(location);
        repository.saveAndFlush(location);
        record(location, "UPDATE", null, null, before, location.getUpdatedAt());
        return view(location);
    }

    /**
     * Refused (409 LOCATION_DEACTIVATE_REFUSED, every reason in details.reasons) for the primary, and
     * while the location has stock on hand, is assigned to a job, or has open stock requests or
     * stock takes.
     */
    @Transactional
    public AdminLocation deactivate(long id) {
        var location = find(id);
        if (!location.isActive()) {
            return view(location);
        }
        store.lockForWrite();
        var reasons = new ArrayList<String>();
        var details = new LinkedHashMap<String, Object>();
        if (location.isPrimary()) {
            reasons.add("PRIMARY");
        }
        var stockCount = store.countStockOnHand(location.getId());
        if (stockCount > 0) {
            reasons.add("HAS_STOCK");
            details.put("ingredientsWithStock", stockCount);
            details.put("stock", store.stockOnHand(location.getId()));
        }
        var roles = assignmentRepository.findByLocationId(location.getId()).stream()
                .map(LocationAssignment::getRole).sorted().toList();
        if (!roles.isEmpty()) {
            reasons.add("ASSIGNED");
            details.put("assignedTo", roles);
        }
        var requests = store.openStockRequestIds(location.getId());
        if (!requests.isEmpty()) {
            reasons.add("OPEN_STOCK_REQUESTS");
            details.put("openStockRequestIds", requests);
        }
        var stockTakes = store.openStockTakeIds(location.getId());
        if (!stockTakes.isEmpty()) {
            reasons.add("OPEN_STOCK_TAKES");
            details.put("openStockTakeIds", stockTakes);
        }
        if (!reasons.isEmpty()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("locationId", location.getId());
            body.put("reasons", reasons);
            body.putAll(details);
            throw new LocationRuleException(HttpStatus.CONFLICT, "LOCATION_DEACTIVATE_REFUSED",
                    location.getName() + " can't be deactivated: " + describe(reasons) + ".", body);
        }
        var before = Values.of(location);
        location.setActive(false);
        stamp(location);
        repository.saveAndFlush(location);
        record(location, "DEACTIVATE", null, null, before, location.getUpdatedAt());
        return view(location);
    }

    /** Checks the name and the maximum again, against the locations active now. */
    @Transactional
    public AdminLocation activate(long id) {
        var location = find(id);
        if (location.isActive()) {
            return view(location);
        }
        store.lockForWrite();
        var all = repository.findAll();
        requireNameFree(location.getName(), location.getId(), all);
        requireBelowLimit(all);
        var before = Values.of(location);
        location.setActive(true);
        stamp(location);
        repository.saveAndFlush(location);
        record(location, "ACTIVATE", null, null, before, location.getUpdatedAt());
        return view(location);
    }

    @Transactional(readOnly = true)
    public Assignments assignments() {
        var byRole = new EnumMap<LocationRole, Location>(LocationRole.class);
        assignmentRepository.findAll().forEach(a -> byRole.put(a.getRole(), a.getLocation()));
        var stations = new LinkedHashMap<Station, LocationRef>();
        for (var station : Station.values()) {
            stations.put(station, LocationRef.of(byRole.get(LocationRole.forStation(station))));
        }
        return new Assignments(LocationRef.of(stockLocations.primary()),
                LocationRef.of(byRole.get(LocationRole.PRODUCTION)), stations);
    }

    /** Replaces what is sent; each location must be an active SECONDARY. */
    @Transactional
    public Assignments updateAssignments(AssignmentsBody body) {
        var wanted = new EnumMap<LocationRole, Long>(LocationRole.class);
        if (body != null && body.productionLocationId() != null) {
            wanted.put(LocationRole.PRODUCTION, body.productionLocationId());
        }
        if (body != null && body.stations() != null) {
            body.stations().forEach((station, locationId) -> {
                if (station != null && locationId != null) {
                    wanted.put(LocationRole.forStation(station), locationId);
                }
            });
        }
        if (wanted.isEmpty()) {
            throw invalid(Map.of("productionLocationId", "Send productionLocationId, stations, or both."));
        }
        store.lockForWrite();
        var now = UtcTimestamps.nowUtc(clock);
        var user = authService.getCurrentUser();
        for (var entry : wanted.entrySet()) {
            var location = repository.findById(entry.getValue())
                    .orElseThrow(() -> LocationRuleException.notFound(entry.getValue()));
            if (!location.isActive()) {
                throw LocationRuleException.inactive(location);
            }
            if (location.isPrimary()) {
                throw new LocationRuleException(HttpStatus.BAD_REQUEST, "ASSIGNMENT_NOT_SECONDARY",
                        location.getName() + " is the primary location; production and the stations use a secondary location.",
                        Map.of("role", entry.getKey(), "locationId", location.getId(), "locationName", location.getName()));
            }
            var assignment = assignmentRepository.findById(entry.getKey()).orElseGet(() -> {
                var created = new LocationAssignment();
                created.setRole(entry.getKey());
                return created;
            });
            var previous = assignment.getLocation();
            if (previous != null && Objects.equals(previous.getId(), location.getId())) {
                continue;
            }
            assignment.setLocation(location);
            assignment.setUpdatedAt(now);
            assignment.setUpdatedBy(user.getId());
            assignment.setUpdatedByName(user.getName());
            assignmentRepository.saveAndFlush(assignment);
            record(location, "ASSIGN", entry.getKey(), previous == null ? null : previous.getId(), null, now);
        }
        return assignments();
    }

    @Transactional(readOnly = true)
    public PageDto<HistoryEntry> history(Long locationId, int page, int size) {
        var safePage = Math.max(page, 0);
        var safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var total = store.countHistory(locationId);
        return new PageDto<>(store.history(locationId, safePage, safeSize), safePage, safeSize, total,
                (int) ((total + safeSize - 1) / safeSize));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private record Fields(String name, Integer sortOrder) {
    }

    private static Fields read(LocationBody body, boolean nameRequired) {
        var errors = new LinkedHashMap<String, Object>();
        var name = body == null || body.name() == null ? null : body.name().trim();
        if (name == null || name.isEmpty()) {
            if (nameRequired) {
                errors.put("name", "name is required.");
            }
        } else if (name.length() > MAX_NAME_LENGTH) {
            errors.put("name", "name can be at most " + MAX_NAME_LENGTH + " characters.");
        }
        var sortOrder = body == null ? null : body.sortOrder();
        if (sortOrder != null && (sortOrder < 0 || sortOrder > MAX_SORT_ORDER)) {
            errors.put("sortOrder", "sortOrder must be between 0 and " + MAX_SORT_ORDER + ".");
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        return new Fields(name, sortOrder);
    }

    private static LocationRuleException invalid(Map<String, Object> fieldErrors) {
        return new LocationRuleException(HttpStatus.BAD_REQUEST, "LOCATION_INVALID",
                "Check the highlighted fields.", Map.of("fields", fieldErrors));
    }

    private Location find(long id) {
        return repository.findById(id).orElseThrow(() -> new LocationRuleException(HttpStatus.NOT_FOUND,
                "LOCATION_NOT_FOUND", "Location " + id + " not found.", Map.of("locationId", id)));
    }

    private static void requireNameFree(String name, Long self, List<Location> all) {
        all.stream()
                .filter(other -> other.isActive() && !Objects.equals(other.getId(), self))
                .filter(other -> other.getName().trim().equalsIgnoreCase(name.trim()))
                .findFirst()
                .ifPresent(other -> {
                    throw new LocationRuleException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN",
                            "Another active location is already called " + other.getName() + ".",
                            Map.of("locationId", other.getId(), "name", other.getName()));
                });
    }

    /** The maximum counts every active location, the primary included. */
    private void requireBelowLimit(List<Location> all) {
        var max = siteSettings.stockLocationsMaxActive();
        var active = all.stream().filter(Location::isActive).count();
        if (active >= max) {
            throw new LocationRuleException(HttpStatus.CONFLICT, "LOCATION_LIMIT_REACHED",
                    "At most " + max + " locations can be active; deactivate one first.",
                    Map.of("maxActive", max, "active", active));
        }
    }

    private static int nextSortOrder(List<Location> all) {
        var max = all.stream().mapToInt(Location::getSortOrder).max().orElse(0);
        return Math.min(max + 10, MAX_SORT_ORDER);
    }

    private void stamp(Location location) {
        var user = authService.getCurrentUser();
        location.setUpdatedAt(UtcTimestamps.nowUtc(clock));
        location.setUpdatedBy(user.getId());
        location.setUpdatedByName(user.getName());
    }

    private void record(Location location, String action, LocationRole role, Long previousLocationId,
                        Values before, LocalDateTime at) {
        var user = authService.getCurrentUser();
        store.insert(location.getId(), action, role, previousLocationId, before, Values.of(location), at,
                user.getId(), user.getName());
    }

    private Map<Long, List<LocationRole>> rolesByLocation() {
        var result = new LinkedHashMap<Long, List<LocationRole>>();
        assignmentRepository.findAll().stream()
                .sorted((a, b) -> a.getRole().compareTo(b.getRole()))
                .forEach(a -> result.computeIfAbsent(a.getLocation().getId(), k -> new ArrayList<>()).add(a.getRole()));
        return result;
    }

    private AdminLocation view(Location location) {
        return view(location, store.stockSummaries(), rolesByLocation());
    }

    private static AdminLocation view(Location location, Map<Long, StockSummary> summaries,
                                      Map<Long, List<LocationRole>> roles) {
        return new AdminLocation(location.getId(), location.getName(), location.getKind(), location.isActive(),
                location.getSortOrder(), summaries.getOrDefault(location.getId(), new StockSummary(0, 0)),
                roles.getOrDefault(location.getId(), List.of()),
                location.getCreatedAt() == null ? null : UtcTimestamps.toInstant(location.getCreatedAt()),
                location.getCreatedByName(),
                location.getUpdatedAt() == null ? null : UtcTimestamps.toInstant(location.getUpdatedAt()),
                location.getUpdatedByName());
    }

    private static String describe(List<String> reasons) {
        var parts = reasons.stream().map(reason -> switch (reason) {
            case "PRIMARY" -> "it is the primary location";
            case "HAS_STOCK" -> "it still has stock on hand";
            case "ASSIGNED" -> "production or a station uses it";
            case "OPEN_STOCK_REQUESTS" -> "it has open stock requests";
            case "OPEN_STOCK_TAKES" -> "it has stock takes waiting for review";
            default -> reason;
        }).toList();
        return String.join("; ", parts);
    }
}
