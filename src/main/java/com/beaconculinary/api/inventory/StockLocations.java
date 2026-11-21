package com.beaconculinary.api.inventory;

import com.beaconculinary.api.menu.Station;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * T0-5c: the one place a stock flow finds its location, by the job it does, never by name.
 *
 * <ul>
 *   <li>{@link #primary()}: GRV receiving, the supplying side of every issue, the legacy stock take.</li>
 *   <li>{@link #production()}: where daily planning's issue sheet sends ingredients.</li>
 *   <li>{@link #sourceFor(Station)}: where a station's sale-time stock is deducted from.</li>
 *   <li>{@link #chosen(Long)}: a location the user picked (waste, stock take, the requesting side).</li>
 * </ul>
 */
@Component
@AllArgsConstructor
public class StockLocations {
    private final LocationRepository locationRepository;
    private final LocationAssignmentRepository assignmentRepository;

    /** The one PRIMARY location (V115 guarantees exactly one). */
    public Location primary() {
        return locationRepository.findFirstByKind(LocationKind.PRIMARY)
                .orElseThrow(() -> new IllegalStateException("No primary location; V115 should have set one."));
    }

    public Location production() {
        return assigned(LocationRole.PRODUCTION);
    }

    /** A null station is treated as KITCHEN, the column's default. */
    public Location sourceFor(Station station) {
        return assigned(LocationRole.forStation(station));
    }

    public Location assigned(LocationRole role) {
        return assignmentRepository.findById(role).map(LocationAssignment::getLocation)
                .orElseThrow(() -> new IllegalStateException("No location assigned to " + role + "; V115 seeds every role."));
    }

    /** A location the user picked: it must exist and be active. Messages as before T0-5c. */
    public Location chosen(Long locationId) {
        if (locationId == null) {
            throw new InvalidInventoryRequestException("locationId is required.");
        }
        var location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InvalidInventoryRequestException("locationId does not exist."));
        if (!location.isActive()) {
            throw new InvalidInventoryRequestException("locationId does not reference an active location.");
        }
        return location;
    }
}
