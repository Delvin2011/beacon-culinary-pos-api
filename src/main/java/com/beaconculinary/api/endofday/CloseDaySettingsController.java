package com.beaconculinary.api.endofday;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Reports R3: the configured first trading day for Close Day, so the frontend can show it
 * (e.g. "Z numbering starts 12 Oct 2026") and not offer Close Day before it. ADMIN only, through
 * AdminSecurityRules' /admin/** rule. firstTradingDay is null when not configured. */
@AllArgsConstructor
@RestController
public class CloseDaySettingsController {
    private final CloseDaySettings settings;

    public record CloseDaySettingsDto(LocalDate firstTradingDay) {
    }

    @GetMapping("/admin/settings/close-day")
    public CloseDaySettingsDto get() {
        return new CloseDaySettingsDto(settings.firstTradingDay());
    }
}
