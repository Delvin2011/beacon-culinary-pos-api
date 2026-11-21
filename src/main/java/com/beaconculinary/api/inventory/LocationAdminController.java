package com.beaconculinary.api.inventory;

import com.beaconculinary.api.inventory.LocationAdminDtos.AdminLocation;
import com.beaconculinary.api.inventory.LocationAdminDtos.Assignments;
import com.beaconculinary.api.inventory.LocationAdminDtos.AssignmentsBody;
import com.beaconculinary.api.inventory.LocationAdminDtos.HistoryEntry;
import com.beaconculinary.api.inventory.LocationAdminDtos.LocationBody;
import com.beaconculinary.api.oversight.PageDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * T0-5c: stock locations for admins (docs/2026-11-21-t0-5c-locations-api-contract.md). ADMIN only,
 * through AdminSecurityRules' /admin/** rule. The pickers' GET /locations stays in
 * LocationController.
 */
@RestController
@RequestMapping("/admin/locations")
public class LocationAdminController {
    private final LocationAdminService service;

    public LocationAdminController(LocationAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminLocation> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminLocation create(@RequestBody(required = false) LocationBody body) {
        return service.create(body);
    }

    @PutMapping("/{id}")
    public AdminLocation update(@PathVariable long id, @RequestBody(required = false) LocationBody body) {
        return service.update(id, body);
    }

    @PostMapping("/{id}/deactivate")
    public AdminLocation deactivate(@PathVariable long id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public AdminLocation activate(@PathVariable long id) {
        return service.activate(id);
    }

    @GetMapping("/assignments")
    public Assignments assignments() {
        return service.assignments();
    }

    @PutMapping("/assignments")
    public Assignments updateAssignments(@RequestBody(required = false) AssignmentsBody body) {
        return service.updateAssignments(body);
    }

    @GetMapping("/history")
    public PageDto<HistoryEntry> history(@RequestParam(required = false) Long locationId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return service.history(locationId, page, size);
    }
}
