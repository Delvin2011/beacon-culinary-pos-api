package com.beaconculinary.api.offline;

import com.beaconculinary.api.offline.OfflineDtos.GrantResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O3 offline endpoints (docs/2026-11-17-o3-offline-foundation-api-contract.md). The grant is for
 * CASHIER and ADMIN through the /pos/** rule in PosSecurityRules; OfflineAccess does the rest.
 */
@RestController
public class OfflineController {
    private final OfflineGrantService grantService;

    public OfflineController(OfflineGrantService grantService) {
        this.grantService = grantService;
    }

    @PostMapping("/pos/offline/grant")
    public ResponseEntity<GrantResponse> grant() {
        return ResponseEntity.status(HttpStatus.CREATED).body(grantService.issue());
    }
}
