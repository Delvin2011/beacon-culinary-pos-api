package com.beaconculinary.api.offline.sync;

import com.beaconculinary.api.common.ErrorDto;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SyncRequest;
import com.beaconculinary.api.offline.sync.OfflineSaleDtos.SyncResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * O4: POST /pos/offline/sales (contract section 2). Open to anyone in PosSecurityRules: the till's
 * cookie and the batch's signed grants are the credentials (OfflineSyncService). JSON only, at most
 * 512 KB.
 */
@RestController
public class OfflineSyncController {
    static final long MAX_BODY_BYTES = 512 * 1024;

    private final OfflineSyncService syncService;

    public OfflineSyncController(OfflineSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping(value = "/pos/offline/sales", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SyncResponse sync(@RequestBody SyncRequest request, HttpServletRequest http) {
        if (http.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        }
        return syncService.sync(request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorDto> unreadable() {
        return ResponseEntity.badRequest().body(new ErrorDto("The batch couldn't be read.", "INVALID_BATCH"));
    }
}
