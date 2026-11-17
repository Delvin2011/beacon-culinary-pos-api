package com.beaconculinary.api.offline;

import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineDtos.GrantResponse;
import com.beaconculinary.api.shifts.ShiftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * O3: issues offline grants (contract sections 5 to 7). Only the owner of this till's open shift,
 * signed in on this till, gets one; an ADMIN covering someone else's shift doesn't (v1). A new grant
 * replaces the till's active grant for that shift, under a lock on the till so two requests at once
 * can't both stay active.
 */
@Service
public class OfflineGrantService {
    private static final int LOCK_TIMEOUT_MILLIS = 5000;

    private final OfflineProperties properties;
    private final OfflineAccess access;
    private final OfflineGrantStore grants;
    private final OfflineSequenceStore sequences;
    private final OfflineGrantSigner signer;
    private final ShiftRepository shiftRepository;
    private final AppLock appLock;
    private final Clock clock;

    public OfflineGrantService(OfflineProperties properties, OfflineAccess access, OfflineGrantStore grants,
                               OfflineSequenceStore sequences, OfflineGrantSigner signer, ShiftRepository shiftRepository,
                               AppLock appLock, Clock clock) {
        this.properties = properties;
        this.access = access;
        this.grants = grants;
        this.sequences = sequences;
        this.signer = signer;
        this.shiftRepository = shiftRepository;
        this.appLock = appLock;
        this.clock = clock;
    }

    @Transactional
    public GrantResponse issue() {
        access.requireEnabled();
        var till = access.requireTill();
        var session = access.requirePosSessionOn(till.deviceId());

        appLock.lock("offline-grant:" + till.deviceId(), LOCK_TIMEOUT_MILLIS);
        var shift = shiftRepository.findOpenOnTill(till.deviceId()).orElseThrow(OfflineException::noOpenShift);
        var owner = shift.getCashier();
        if (owner.getId() != session.userId()) {
            throw OfflineException.notShiftOwner(owner.getName());
        }

        // JWT times have whole seconds; the stored times match the signed ones exactly.
        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expiresAt = issuedAt.plus(properties.getGrantMaxHours(), ChronoUnit.HOURS);
        var id = UUID.randomUUID();
        grants.replaceActive(till.deviceId(), shift.getId(), id, UtcTimestamps.toUtc(issuedAt));
        grants.insert(new OfflineGrantStore.GrantRow(id, till.deviceId(), till.credentialId(), shift.getId(), owner.getId(),
                owner.getName(), session.id(), UtcTimestamps.toUtc(issuedAt), UtcTimestamps.toUtc(expiresAt),
                null, null, null));

        var tradingDay = LocalDate.now(clock);
        var signed = signer.sign(new OfflineGrantSigner.Claimed(id, till.deviceId(), till.credentialId(), shift.getId(),
                owner.getId(), issuedAt, expiresAt));
        return new GrantResponse(id.toString(), signed, till.deviceId().toString(), shift.getTillCode(), shift.getId(),
                owner.getId(), owner.getName(), issuedAt, expiresAt, tradingDay,
                sequences.nextFor(till.deviceId(), tradingDay), properties.getMaxOfflineHours(),
                properties.getWarnOfflineHours(), properties.isAccountsEnabled(),
                properties.isAccountsEnabled() ? properties.getAccountCap() : null, clock.instant());
    }
}
