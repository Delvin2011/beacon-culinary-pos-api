package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.Station;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** T0-5c: bodies and views of /admin/locations (docs/2026-11-21-t0-5c-locations-api-contract.md). */
public final class LocationAdminDtos {
    private LocationAdminDtos() {
    }

    /** POST /admin/locations and PUT /admin/locations/{id}. sortOrder optional (create: last; update: unchanged). */
    public record LocationBody(String name, Integer sortOrder) {
    }

    /** PUT /admin/locations/assignments. Each part optional; what is sent is replaced. */
    public record AssignmentsBody(Long productionLocationId, Map<Station, Long> stations) {
    }

    /** Ingredients whose stock at the location is not zero (negative counts too). */
    public record StockSummary(int ingredientsWithStock, int ingredientsNegative) {
    }

    public record AdminLocation(
            Long id,
            String name,
            LocationKind kind,
            boolean active,
            int sortOrder,
            StockSummary stockSummary,
            // The jobs this location is assigned to (PRODUCTION, STATION_*); empty for the primary.
            List<LocationRole> assignedTo,
            Instant createdAt,
            String createdByName,
            Instant updatedAt,
            String updatedByName) {
    }

    public record LocationRef(Long id, String name) {
        static LocationRef of(Location location) {
            return new LocationRef(location.getId(), location.getName());
        }
    }

    /** GET/PUT /admin/locations/assignments. stations always has KITCHEN, BAR and NONE. */
    public record Assignments(LocationRef primary, LocationRef production, Map<Station, LocationRef> stations) {
    }

    /** One non-zero stock figure, in a deactivation refusal. */
    public record StockLine(Long ingredientId, String name, IngredientUnit unit, BigDecimal quantity) {
    }

    public record Values(String name, Integer sortOrder, Boolean active) {
        static Values of(Location location) {
            return new Values(location.getName(), location.getSortOrder(), location.isActive());
        }
    }

    /** One location_changes row. role and previousLocation: ASSIGN only. before: null for CREATE and ASSIGN. */
    public record HistoryEntry(
            Long id,
            Long locationId,
            String action,
            LocationRole role,
            Long previousLocationId,
            Values before,
            Values after,
            Instant changedAt,
            Long changedBy,
            String changedByName) {
    }
}
