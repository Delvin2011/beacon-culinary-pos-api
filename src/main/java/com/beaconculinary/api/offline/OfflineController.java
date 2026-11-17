package com.beaconculinary.api.offline;

import com.beaconculinary.api.menu.AlwaysOnMenuService;
import com.beaconculinary.api.offline.OfflineDtos.GrantResponse;
import com.beaconculinary.api.offline.OfflineDtos.Snapshot;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * O3 offline endpoints (docs/2026-11-17-o3-offline-foundation-api-contract.md). Grant and snapshot
 * are for CASHIER and ADMIN through the /pos/** rule in PosSecurityRules; OfflineAccess does the rest.
 */
@RestController
public class OfflineController {
    private final OfflineGrantService grantService;
    private final OfflineSnapshotService snapshotService;
    private final OfflineAccess access;
    private final AlwaysOnMenuService alwaysOnMenuService;

    public OfflineController(OfflineGrantService grantService, OfflineSnapshotService snapshotService,
                             OfflineAccess access, AlwaysOnMenuService alwaysOnMenuService) {
        this.grantService = grantService;
        this.snapshotService = snapshotService;
        this.access = access;
        this.alwaysOnMenuService = alwaysOnMenuService;
    }

    @PostMapping("/pos/offline/grant")
    public ResponseEntity<GrantResponse> grant() {
        return ResponseEntity.status(HttpStatus.CREATED).body(grantService.issue());
    }

    /** 200 with an ETag, or 304 when If-None-Match names the current version. */
    @GetMapping("/pos/offline/snapshot")
    public ResponseEntity<Snapshot> snapshot(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        access.requireEnabled();
        var till = access.requireTill();
        access.requirePosSessionOn(till.deviceId());
        // As GET /menu/today: today's always-on options are created first, outside the read transaction.
        alwaysOnMenuService.ensureAlwaysOnOptionsToday();

        var snapshot = snapshotService.build();
        var etag = "\"" + snapshot.snapshotVersion() + "\"";
        var cache = CacheControl.noCache().cachePrivate();
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(cache).body(snapshot);
    }

    /** If-None-Match may list several tags, weak or strong; any one equal to ours matches. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (var candidate : ifNoneMatch.split(",")) {
            var tag = candidate.trim();
            if (tag.startsWith("W/")) {
                tag = tag.substring(2);
            }
            if (tag.equals(etag) || tag.equals("*")) {
                return true;
            }
        }
        return false;
    }
}
