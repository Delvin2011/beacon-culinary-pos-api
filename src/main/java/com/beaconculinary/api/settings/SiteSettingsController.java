package com.beaconculinary.api.settings;

import com.beaconculinary.api.oversight.PageDto;
import com.beaconculinary.api.settings.SiteSettingsDtos.HistoryEntry;
import com.beaconculinary.api.settings.SiteSettingsDtos.SettingsView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T0-4a: the customer's settings (docs/2026-11-19-t0-4-site-settings-api-contract.md). ADMIN only,
 * through AdminSecurityRules' /admin/** rule. GET /admin/settings/vat and /admin/settings/close-day
 * (older read-only endpoints) stay in their own controllers.
 */
@RestController
@RequestMapping("/admin/settings")
public class SiteSettingsController {
    private final SiteSettingsAdminService service;
    private final ObjectMapper objectMapper;

    public SiteSettingsController(SiteSettingsAdminService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public SettingsView list() {
        return service.view();
    }

    /** The body is read as text and parsed with decimals kept exact (0.15 stays 0.15, not a double). */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView update(@RequestBody(required = false) String body) {
        return service.update(parse(body));
    }

    @PostMapping("/{key}/reset")
    public SettingsView reset(@PathVariable String key) {
        return service.reset(key);
    }

    @GetMapping("/history")
    public PageDto<HistoryEntry> history(@RequestParam(required = false) String key,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "50") int size) {
        return service.history(key, page, size);
    }

    private Map<String, JsonNode> parse(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        JsonNode tree;
        try {
            tree = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
        } catch (JsonProcessingException e) {
            tree = null;
        }
        if (tree == null || !tree.isObject()) {
            throw new SiteSettingsValidationException(Map.of("body", "Must be a JSON object of settings, e.g. {\"board.done-display-minutes\": 5}."));
        }
        var values = new LinkedHashMap<String, JsonNode>();
        tree.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue()));
        return values;
    }
}
