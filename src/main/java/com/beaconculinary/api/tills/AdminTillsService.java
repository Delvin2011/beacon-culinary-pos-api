package com.beaconculinary.api.tills;

import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.offline.OfflineStatusDtos;
import com.beaconculinary.api.offline.OfflineStatusService;
import com.beaconculinary.api.devices.DeviceStore;
import com.beaconculinary.api.sessions.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * O2: every active till with its open shift and lock state (GET /admin/tills), for the manager's
 * "Tills" view. Sorted by till code; the unregistered till is listed last, and only while it has an
 * open shift. Revoked tills aren't listed (GET /admin/devices has them).
 */
@Service
public class AdminTillsService {
    private final DeviceStore deviceStore;
    private final SessionService sessionService;
    private final JdbcTemplate jdbc;
    private final OfflineStatusService offlineStatus;

    public AdminTillsService(DeviceStore deviceStore, SessionService sessionService, JdbcTemplate jdbc,
                             OfflineStatusService offlineStatus) {
        this.deviceStore = deviceStore;
        this.sessionService = sessionService;
        this.jdbc = jdbc;
        this.offlineStatus = offlineStatus;
    }

    private record OpenShift(long shiftId, long ownerId, String ownerName, String deviceId, LocalDateTime openedAtUtc) {
    }

    @Transactional(readOnly = true)
    public List<AdminTillDto> tills() {
        var openByDevice = new HashMap<String, OpenShift>();
        OpenShift unregistered = null;
        for (var shift : openShifts()) {
            if (shift.deviceId() == null) {
                unregistered = shift;
            } else {
                openByDevice.put(shift.deviceId(), shift);
            }
        }

        var tills = new ArrayList<AdminTillDto>();
        var active = deviceStore.listDevices().stream()
                .filter(DeviceStore.DeviceRow::active)
                .sorted(Comparator.comparing((DeviceStore.DeviceRow d) -> d.tillCode() == null ? "" : d.tillCode())
                        .thenComparing(DeviceStore.DeviceRow::name))
                .toList();
        var offline = offlineStatus.forAdmin();
        for (var device : active) {
            var open = openByDevice.get(device.id());
            AdminTillDto.Shift shift = null;
            if (open != null) {
                var locked = !sessionService.hasActivePosSession(open.ownerId(), UUID.fromString(device.id()));
                shift = new AdminTillDto.Shift(open.shiftId(), open.ownerId(), open.ownerName(),
                        UtcTimestamps.toInstant(open.openedAtUtc()), locked);
            }
            tills.add(new AdminTillDto(device.id(), device.tillCode(), device.name(), true, shift,
                    offline.getOrDefault(UUID.fromString(device.id()), OfflineStatusDtos.TillOffline.NEVER)));
        }
        if (unregistered != null) {
            tills.add(new AdminTillDto(null, null, Till.UNREGISTERED_NAME, false, new AdminTillDto.Shift(
                    unregistered.shiftId(), unregistered.ownerId(), unregistered.ownerName(),
                    UtcTimestamps.toInstant(unregistered.openedAtUtc()), null), null));
        }
        return tills;
    }

    private List<OpenShift> openShifts() {
        return jdbc.query("""
                SELECT s.[id], s.[cashier_id], u.[name], s.[device_id], s.[opened_at]
                FROM [shifts] s JOIN [users] u ON u.[id] = s.[cashier_id]
                WHERE s.[status] = 'OPEN'
                """, (rs, i) -> {
            var device = rs.getString("device_id");
            return new OpenShift(rs.getLong("id"), rs.getLong("cashier_id"), rs.getString("name"),
                    device == null ? null : device.toLowerCase(java.util.Locale.ROOT),
                    rs.getObject("opened_at", LocalDateTime.class));
        });
    }
}
