package com.beaconculinary.api.inventory;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reference data for dropdowns — falls through to the default {@code authenticated()} rule in
 * {@code SecurityConfig} (any role), same as every other unmatched endpoint. */
@AllArgsConstructor
@RestController
@RequestMapping("/locations")
public class LocationController {
    private final LocationRepository locationRepository;
    private final InventoryMapper inventoryMapper;

    @GetMapping
    public List<LocationDto> getAll() {
        return locationRepository.findAll().stream().map(inventoryMapper::toDto).toList();
    }
}
