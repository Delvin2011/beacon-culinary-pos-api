package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reference data for dropdowns — falls through to the default {@code authenticated()} rule in
 * {@code SecurityConfig} (any role), same as every other unmatched endpoint.
 *
 * <p>T0-5c: active locations only, in sort order, with their kind (the waste, stock take and
 * request pickers). {@code includeInactive=true} adds deactivated ones, for history filters.
 * Managing locations is at /admin/locations. */
@AllArgsConstructor
@RestController
@RequestMapping("/locations")
public class LocationController {
    private final LocationRepository locationRepository;
    private final InventoryMapper inventoryMapper;

    @GetMapping
    public List<LocationDto> getAll(@RequestParam(defaultValue = "false") boolean includeInactive) {
        var locations = includeInactive
                ? locationRepository.findAllByOrderBySortOrderAscIdAsc()
                : locationRepository.findByActiveTrueOrderBySortOrderAscIdAsc();
        return locations.stream().map(inventoryMapper::toDto).toList();
    }
}
