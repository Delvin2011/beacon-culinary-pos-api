package com.beaconculinary.api.devices;

import com.beaconculinary.api.auth.RecentPasswordCheck;
import com.beaconculinary.api.auth.protection.ClientIpResolver;
import com.beaconculinary.api.common.AppLock;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineGrantStore;
import com.beaconculinary.api.sessions.SessionEndReason;
import com.beaconculinary.api.sessions.SessionStore;
import com.beaconculinary.api.tills.TillCodes;
import com.beaconculinary.api.tills.TillProperties;
import com.beaconculinary.api.users.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * POS till registration (WS1-1b, 3-B). Registering, editing and revoking need an ADMIN signed in
 * with a password within reauth-max-age (decision K2); see the device registration API contract.
 * O2: a new till gets the lowest free till code and must fit under pos.tills.max-active; an admin can
 * edit the code (docs/2026-11-17-o2-tills-shifts-api-contract.md, 8.1).
 */
@Service
public class DeviceService {
    /** O2: serialises new-till registration and till-code edits, so two can't take one code or both pass the limit. */
    static final String TILL_CODES_LOCK = "till-codes";
    private static final int TILL_CODES_LOCK_TIMEOUT_MILLIS = 15_000;

    private final DeviceTrustProperties properties;
    private final TillProperties tillProperties;
    private final AppLock appLock;
    private final SessionStore sessionStore;
    private final DeviceStore store;
    private final DeviceCookies cookies;
    private final DeviceAlerts alerts;
    private final RecentPasswordCheck recentPasswordCheck;
    private final ClientIpResolver clientIpResolver;
    private final OfflineGrantStore offlineGrants;
    private final Clock clock;

    public DeviceService(DeviceTrustProperties properties, TillProperties tillProperties, AppLock appLock,
                         SessionStore sessionStore, DeviceStore store, DeviceCookies cookies, DeviceAlerts alerts,
                         RecentPasswordCheck recentPasswordCheck, ClientIpResolver clientIpResolver,
                         OfflineGrantStore offlineGrants, Clock clock) {
        this.properties = properties;
        this.tillProperties = tillProperties;
        this.appLock = appLock;
        this.sessionStore = sessionStore;
        this.store = store;
        this.cookies = cookies;
        this.alerts = alerts;
        this.recentPasswordCheck = recentPasswordCheck;
        this.clientIpResolver = clientIpResolver;
        this.offlineGrants = offlineGrants;
        this.clock = clock;
    }

    public DeviceDtos.Config config() {
        return new DeviceDtos.Config(properties.getMode().name().toLowerCase(Locale.ROOT),
                properties.isMultiBrowserEnabled(), properties.isRequireSerial(),
                tillProperties.getTills().getMaxActive(), tillProperties.getTills().isRequireRegistered());
    }

    /** Registers the browser making the request (B9, P1, P2). The token goes only into the cookie. */
    @Transactional
    public DeviceDtos.Device register(DeviceRequests.Register request, HttpServletRequest http, HttpServletResponse response) {
        var admin = requireRecentPassword(http);
        var context = DeviceContext.current();
        if (context.valid()) {
            throw DeviceException.alreadyRegistered(context);
        }
        var errors = new LinkedHashMap<String, String>();
        var name = requireName(request.getName(), errors);
        var serial = checkSerial(request.getSerialNumber(), errors);
        var assetTag = checkAssetTag(request.getAssetTag(), errors);
        if (!errors.isEmpty()) {
            throw new DeviceValidationException(errors);
        }
        var joinDeviceId = blank(request.getJoinDeviceId()) ? null : DeviceStore.id(request.getJoinDeviceId().trim());
        if (joinDeviceId != null && !properties.isMultiBrowserEnabled()) {
            throw DeviceException.joinDisabled();
        }

        var now = UtcTimestamps.nowUtc(clock);
        var existing = store.findActiveBySerial(serial).orElse(null);
        String deviceId;
        boolean joined;
        if (existing != null) {
            var usable = store.countUsableCredentials(existing.id(), idleCutoff(now));
            if (joinDeviceId != null && !joinDeviceId.equals(existing.id())) {
                throw DeviceException.joinMismatch();
            }
            if (usable > 0 && !properties.isMultiBrowserEnabled()) {
                throw DeviceException.serialInUse(existing);
            }
            if (usable > 0 && joinDeviceId == null) {
                throw DeviceException.serialExists(existing);
            }
            // No active browser left (P1), or a confirmed join (B9): same till.
            deviceId = existing.id();
            joined = true;
        } else {
            if (joinDeviceId != null) {
                throw DeviceException.joinMismatch();
            }
            store.findActiveByAssetTag(assetTag).ifPresent(other -> {
                throw DeviceException.assetTagInUse(other);
            });
            // O2 R1/R2: a new till (never a join) must fit under the limit, and gets the lowest free code.
            appLock.lock(TILL_CODES_LOCK, TILL_CODES_LOCK_TIMEOUT_MILLIS);
            var maxActive = tillProperties.getTills().getMaxActive();
            var active = store.countActiveDevices();
            if (active >= maxActive) {
                throw DeviceException.tillLimitReached(maxActive, active);
            }
            var tillCode = TillCodes.lowestFree(store.activeTillCodes());
            try {
                deviceId = store.insertDevice(name, serial, assetTag, tillCode, admin.getId(), admin.getName(), now);
            } catch (DuplicateKeyException race) {
                throw new DeviceException(HttpStatus.CONFLICT, "DEVICE_SERIAL_IN_USE",
                        "This serial number or asset tag was just registered to another till.");
            }
            joined = false;
        }

        var token = DeviceTokens.newToken();
        var credentialId = store.insertCredential(deviceId, DeviceTokens.hash(token),
                BrowserLabels.of(http.getHeader(HttpHeaders.USER_AGENT)), admin.getId(), admin.getName(), clientIp(http), now);
        cookies.write(response, token);
        alerts.alert(joined ? "DEVICE_JOINED" : "DEVICE_REGISTERED",
                "device=" + deviceId + " credential=" + credentialId + " by=" + admin.getId());
        return toDto(store.findDevice(deviceId).orElseThrow(), new DeviceContext(DeviceContext.Status.VALID, deviceId, credentialId, null), now);
    }

    @Transactional(readOnly = true)
    public List<DeviceDtos.Device> list() {
        var context = DeviceContext.current();
        var now = UtcTimestamps.nowUtc(clock);
        Map<String, List<DeviceStore.CredentialRow>> credentials = store.listCredentials().stream()
                .collect(Collectors.groupingBy(DeviceStore.CredentialRow::deviceId));
        var result = new ArrayList<DeviceDtos.Device>();
        for (var device : store.listDevices()) {
            result.add(toDto(device, credentials.getOrDefault(device.id(), List.of()), context, now));
        }
        return result;
    }

    @Transactional
    public DeviceDtos.Device update(String id, DeviceRequests.Update request, HttpServletRequest http) {
        requireRecentPassword(http);
        var device = loadDevice(id);
        if (!device.active()) {
            throw DeviceException.revoked();
        }
        var errors = new LinkedHashMap<String, String>();
        var name = requireName(request.getName(), errors);
        var serial = checkSerial(request.getSerialNumber(), errors);
        var assetTag = checkAssetTag(request.getAssetTag(), errors);
        var tillCode = checkTillCode(request.getTillCode(), errors);
        if (!errors.isEmpty()) {
            throw new DeviceValidationException(errors);
        }
        store.findActiveBySerial(serial).filter(other -> !other.id().equals(device.id())).ifPresent(other -> {
            throw DeviceException.serialInUse(other);
        });
        store.findActiveByAssetTag(assetTag).filter(other -> !other.id().equals(device.id())).ifPresent(other -> {
            throw DeviceException.assetTagInUse(other);
        });
        if (tillCode != null && !tillCode.equals(device.tillCode())) {
            appLock.lock(TILL_CODES_LOCK, TILL_CODES_LOCK_TIMEOUT_MILLIS);
            store.findActiveByTillCode(tillCode).filter(other -> !other.id().equals(device.id())).ifPresent(other -> {
                throw DeviceException.tillCodeInUse(other);
            });
        }
        store.updateDevice(device.id(), name, serial, assetTag, tillCode != null ? tillCode : device.tillCode());
        return current(device.id());
    }

    @Transactional
    public DeviceDtos.Device updateLabel(String id, String credentialId, DeviceRequests.Label request, HttpServletRequest http) {
        requireRecentPassword(http);
        var device = loadDevice(id);
        var credential = loadCredential(device.id(), credentialId);
        if (!credential.active()) {
            throw DeviceException.revoked();
        }
        var label = request == null || blank(request.getBrowserLabel()) ? null : request.getBrowserLabel().trim();
        if (label != null && label.length() > 100) {
            throw new DeviceValidationException(Map.of("browserLabel", "Browser label must be 100 characters or fewer."));
        }
        store.updateBrowserLabel(credential.id(), label);
        return current(device.id());
    }

    /** Revokes the till and all its browsers. Revoking a revoked till changes nothing. */
    @Transactional
    public DeviceDtos.Device revokeDevice(String id, DeviceRequests.Revoke request, HttpServletRequest http) {
        var admin = requireRecentPassword(http);
        var device = loadDevice(id);
        var reason = reason(request);
        var now = UtcTimestamps.nowUtc(clock);
        if (store.revokeDevice(device.id(), admin.getId(), admin.getName(), reason, now)) {
            // O2: every session on the till ends at once (ADMIN); a shift open there stays open.
            sessionStore.endActiveOnDevice(UUID.fromString(device.id()), SessionEndReason.ADMIN, now);
            // O3: the till's offline grants end too (kept for O4's sync).
            offlineGrants.revokeForDevice(UUID.fromString(device.id()), OfflineGrantStore.RevokeReason.TILL_REVOKED, now);
            alerts.alert("DEVICE_REVOKED", "device=" + device.id() + " by=" + admin.getId());
        }
        return current(device.id());
    }

    /** Revokes one browser. The till stays active even with no browsers left (P1). */
    @Transactional
    public DeviceDtos.Device revokeCredential(String id, String credentialId, DeviceRequests.Revoke request,
                                              HttpServletRequest http) {
        var admin = requireRecentPassword(http);
        var device = loadDevice(id);
        var credential = loadCredential(device.id(), credentialId);
        var reason = reason(request);
        var now = UtcTimestamps.nowUtc(clock);
        if (store.revokeCredential(credential.id(), admin.getId(), admin.getName(), reason, now)) {
            sessionStore.endActiveOnCredential(UUID.fromString(credential.id()), SessionEndReason.ADMIN, now);
            offlineGrants.revokeForCredential(UUID.fromString(credential.id()), OfflineGrantStore.RevokeReason.BROWSER_REVOKED, now);
            alerts.alert("DEVICE_CREDENTIAL_REVOKED", "device=" + device.id() + " credential=" + credential.id() + " by=" + admin.getId());
        }
        return current(device.id());
    }

    // ---- helpers ----

    /** K2: an ADMIN session from a password sign-in no older than reauth-max-age. */
    private User requireRecentPassword(HttpServletRequest http) {
        return recentPasswordCheck.require(http, "manage tills");
    }

    private DeviceStore.DeviceRow loadDevice(String id) {
        if (!isUuid(id)) {
            throw new DeviceNotFoundException();
        }
        return store.findDevice(DeviceStore.id(id)).orElseThrow(DeviceNotFoundException::new);
    }

    private DeviceStore.CredentialRow loadCredential(String deviceId, String credentialId) {
        if (!isUuid(credentialId)) {
            throw new DeviceNotFoundException();
        }
        return store.findCredential(deviceId, DeviceStore.id(credentialId)).orElseThrow(DeviceNotFoundException::new);
    }

    private DeviceDtos.Device current(String deviceId) {
        var now = UtcTimestamps.nowUtc(clock);
        return toDto(store.findDevice(deviceId).orElseThrow(), store.credentialsOf(deviceId), DeviceContext.current(), now);
    }

    private DeviceDtos.Device toDto(DeviceStore.DeviceRow device, DeviceContext context, LocalDateTime now) {
        return toDto(device, store.credentialsOf(device.id()), context, now);
    }

    private DeviceDtos.Device toDto(DeviceStore.DeviceRow device, List<DeviceStore.CredentialRow> credentials,
                                    DeviceContext context, LocalDateTime now) {
        var cutoff = idleCutoff(now);
        var credentialDtos = credentials.stream().map(c -> new DeviceDtos.Credential(
                c.id(), c.browserLabel(),
                !c.active() ? "REVOKED" : c.lastUsed().isBefore(cutoff) ? "EXPIRED" : "ACTIVE",
                c.createdByName(), UtcTimestamps.toInstant(c.createdAt()), UtcTimestamps.toInstant(c.lastSeenAt()),
                c.lastSeenIp(), c.revokedByName(), UtcTimestamps.toInstant(c.revokedAt()), c.revokeReason(),
                c.id().equals(context.credentialId()) && context.valid())).toList();
        return new DeviceDtos.Device(device.id(), device.name(), device.tillCode(), device.type(), device.serialNumber(), device.assetTag(),
                device.status(), device.registeredByName(), UtcTimestamps.toInstant(device.registeredAt()),
                device.revokedByName(), UtcTimestamps.toInstant(device.revokedAt()), device.revokeReason(),
                device.id().equals(context.deviceId()) && context.valid(), credentialDtos);
    }

    private LocalDateTime idleCutoff(LocalDateTime now) {
        return now.minus(Duration.ofDays(properties.getIdleExpiryDays()));
    }

    private String clientIp(HttpServletRequest http) {
        return clientIpResolver.resolve(http.getRemoteAddr(), http.getHeader("X-Forwarded-For"));
    }

    private static String requireName(String raw, Map<String, String> errors) {
        if (blank(raw)) {
            errors.put("name", "Name is required.");
            return null;
        }
        var name = raw.trim();
        if (name.length() > 100) {
            errors.put("name", "Name must be 100 characters or fewer.");
        }
        return name;
    }

    private String checkSerial(String raw, Map<String, String> errors) {
        var serial = DeviceTokens.normaliseSerial(raw);
        if (serial == null) {
            if (properties.isRequireSerial()) {
                errors.put("serialNumber", "Serial number is required.");
            }
            return null;
        }
        if (serial.length() > 100) {
            errors.put("serialNumber", "Serial number must be 100 characters or fewer.");
        }
        return serial;
    }

    private static String checkAssetTag(String raw, Map<String, String> errors) {
        var tag = DeviceTokens.normaliseAssetTag(raw);
        if (tag != null && tag.length() > 100) {
            errors.put("assetTag", "Asset tag must be 100 characters or fewer.");
        }
        return tag;
    }

    /** O2 R1: null (field omitted) keeps the code; anything else must be a valid code once trimmed and upper-cased. */
    private static String checkTillCode(String raw, Map<String, String> errors) {
        if (raw == null) {
            return null;
        }
        var code = TillCodes.normalise(raw);
        if (!TillCodes.isValid(code)) {
            errors.put("tillCode", "Till code must be 1–10 characters: letters A–Z, digits and hyphens.");
        }
        return code;
    }

    private static String reason(DeviceRequests.Revoke request) {
        if (request == null || blank(request.getReason())) {
            return null;
        }
        var reason = request.getReason().trim();
        if (reason.length() > 255) {
            throw new DeviceValidationException(Map.of("reason", "Reason must be 255 characters or fewer."));
        }
        return reason;
    }

    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
