package com.beaconculinary.api.offline;

import com.beaconculinary.api.offline.OfflineStatusDtos.StatusAck;
import com.beaconculinary.api.offline.OfflineStatusDtos.StatusReport;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * O3: POST /pos/offline/status, the till's heartbeat (contract section 9). Open to anyone in
 * PosSecurityRules; the till cookie is the only credential (OfflineAccess.requireTill). JSON only, so
 * a page on another site can't send it with the till's cookie without a CORS preflight.
 */
@RestController
public class OfflineStatusController {
    private final OfflineStatusService statusService;

    public OfflineStatusController(OfflineStatusService statusService) {
        this.statusService = statusService;
    }

    @PostMapping(value = "/pos/offline/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StatusAck report(@Valid @RequestBody StatusReport report) {
        return statusService.report(report);
    }
}
