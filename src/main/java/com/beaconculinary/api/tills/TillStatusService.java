package com.beaconculinary.api.tills;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.sessions.SessionService;
import com.beaconculinary.api.shifts.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** O2 R12: the till status before sign-in (GET /pos/till-status). */
@Service
public class TillStatusService {
    private final CurrentTill currentTill;
    private final ShiftRepository shiftRepository;
    private final SessionService sessionService;
    private final TillProperties properties;

    public TillStatusService(CurrentTill currentTill, ShiftRepository shiftRepository, SessionService sessionService,
                             TillProperties properties) {
        this.currentTill = currentTill;
        this.shiftRepository = shiftRepository;
        this.sessionService = sessionService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TillStatusDto status() {
        var idleLockMinutes = properties.getSession().getIdleLockMinutes();
        var requireRegistered = properties.getTills().isRequireRegistered();
        var till = currentTill.resolve();
        if (!till.registered()) {
            // Owner names are never shown to a browser that isn't a registered till.
            return new TillStatusDto(false, null, null, null, idleLockMinutes, requireRegistered);
        }
        var shift = shiftRepository.findOpenOnTill(till.deviceId()).map(open -> {
            var owner = open.getCashier();
            var locked = !sessionService.hasActivePosSession(owner.getId(), till.deviceId());
            return new TillStatusDto.Shift(open.getId(), owner.getId(), owner.getName(),
                    UtcTimestamps.toInstant(open.getOpenedAt()), locked);
        }).orElse(null);
        return new TillStatusDto(true, till.code(), till.name(), shift, idleLockMinutes, requireRegistered);
    }
}
