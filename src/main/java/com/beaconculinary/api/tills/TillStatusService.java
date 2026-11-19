package com.beaconculinary.api.tills;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineProperties;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.settings.SiteSettings;
import com.beaconculinary.api.shifts.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** O2 R12: the till status before sign-in (GET /pos/till-status). */
@Service
public class TillStatusService {
    private final CurrentTill currentTill;
    private final ShiftRepository shiftRepository;
    private final SessionService sessionService;
    private final SiteSettings siteSettings;
    private final OfflineProperties offlineProperties;

    public TillStatusService(CurrentTill currentTill, ShiftRepository shiftRepository, SessionService sessionService,
                             SiteSettings siteSettings, OfflineProperties offlineProperties) {
        this.currentTill = currentTill;
        this.shiftRepository = shiftRepository;
        this.sessionService = sessionService;
        this.siteSettings = siteSettings;
        this.offlineProperties = offlineProperties;
    }

    @Transactional(readOnly = true)
    public TillStatusDto status() {
        var idleLockMinutes = siteSettings.sessionIdleLockMinutes();
        var requireRegistered = siteSettings.tillsRequireRegistered();
        var till = currentTill.resolve();
        if (!till.registered()) {
            // Owner names are never shown to a browser that isn't a registered till.
            return new TillStatusDto(false, null, null, null, idleLockMinutes, requireRegistered,
                    offlineProperties.isEnabled(), siteSettings.offlineMaxOfflineHours(), siteSettings.offlineWarnOfflineHours());
        }
        var shift = shiftRepository.findOpenOnTill(till.deviceId()).map(open -> {
            var owner = open.getCashier();
            var locked = !sessionService.hasActivePosSession(owner.getId(), till.deviceId());
            return new TillStatusDto.Shift(open.getId(), owner.getId(), owner.getName(),
                    UtcTimestamps.toInstant(open.getOpenedAt()), locked);
        }).orElse(null);
        return new TillStatusDto(true, till.code(), till.name(), shift, idleLockMinutes, requireRegistered,
                offlineProperties.isEnabled(), siteSettings.offlineMaxOfflineHours(), siteSettings.offlineWarnOfflineHours());
    }
}
