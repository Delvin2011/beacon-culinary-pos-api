package com.beaconculinary.api.menu;

import com.beaconculinary.api.menu.MealPeriodAdminDtos.AdminMealPeriod;
import com.beaconculinary.api.menu.MealPeriodAdminDtos.HistoryEntry;
import com.beaconculinary.api.oversight.PageDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * T0-5a: meal periods for admins (docs/2026-11-20-t0-5-meal-periods-locations-api-contract.md).
 * ADMIN only, through AdminSecurityRules' /admin/** rule. The public GET /meal-periods stays in
 * MealPeriodController.
 */
@RestController
@RequestMapping("/admin/meal-periods")
public class MealPeriodAdminController {
    private final MealPeriodAdminService service;
    private final ObjectMapper objectMapper;

    public MealPeriodAdminController(MealPeriodAdminService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<AdminMealPeriod> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminMealPeriod create(@RequestBody(required = false) String body) {
        return service.create(parse(body));
    }

    @PutMapping("/{id}")
    public AdminMealPeriod update(@PathVariable long id, @RequestBody(required = false) String body) {
        return service.update(id, parse(body));
    }

    @PostMapping("/{id}/deactivate")
    public AdminMealPeriod deactivate(@PathVariable long id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public AdminMealPeriod activate(@PathVariable long id) {
        return service.activate(id);
    }

    @GetMapping("/history")
    public PageDto<HistoryEntry> history(@RequestParam(required = false) Long periodId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return service.history(periodId, page, size);
    }

    /** Read as text so a missing, invalid or non-object body is a 400 field map like any other field error. */
    private JsonNode parse(String body) {
        JsonNode tree = null;
        if (body != null && !body.isBlank()) {
            try {
                tree = objectMapper.readTree(body);
            } catch (JsonProcessingException e) {
                tree = null;
            }
        }
        if (tree == null || !tree.isObject()) {
            throw new MealPeriodValidationException(Map.of("body", "Must be a JSON object."));
        }
        return tree;
    }
}
