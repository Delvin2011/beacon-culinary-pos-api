package com.beaconculinary.api.shifts;

import com.beaconculinary.api.tills.Till;
import com.beaconculinary.api.tills.TillException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * O2 R3/R6: why a shift can't open. One open shift per till (the unregistered till counts as one)
 * and one per cashier, ADMIN included; enforced by UX_shifts_open_device and UX_shifts_open_cashier
 * (V107), and explained here with the details the contract gives.
 *
 * <p>Plain SQL rather than the repository: it runs both before the insert and after the unique
 * index has refused one, when the Hibernate session can no longer be flushed.
 */
@Component
public class OpenShiftConflicts {
    private final JdbcTemplate jdbc;

    public OpenShiftConflicts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An open shift, with the till as it was when the shift opened. */
    public record OpenShiftRow(long id, long cashierId, String ownerName, UUID deviceId, String tillCode, String tillName) {
        public Till till() {
            return deviceId == null ? Till.UNREGISTERED : new Till(deviceId, tillCode, tillName);
        }
    }

    private static final String SELECT = """
            SELECT s.[id], s.[cashier_id], u.[name], s.[device_id], s.[till_code], s.[till_name]
            FROM [shifts] s JOIN [users] u ON u.[id] = s.[cashier_id]
            WHERE s.[status] = 'OPEN' AND\s""";

    private static final RowMapper<OpenShiftRow> ROW = (rs, i) -> {
        var device = rs.getString(4);
        return new OpenShiftRow(rs.getLong(1), rs.getLong(2), rs.getString(3),
                device == null ? null : UUID.fromString(device), rs.getString(5), rs.getString(6));
    };

    public Optional<OpenShiftRow> ofCashier(long cashierId) {
        return jdbc.query(SELECT + "s.[cashier_id] = ?", ROW, cashierId).stream().findFirst();
    }

    public Optional<OpenShiftRow> onTill(UUID deviceId) {
        return deviceId == null
                ? jdbc.query(SELECT + "s.[device_id] IS NULL", ROW).stream().findFirst()
                : jdbc.query(SELECT + "s.[device_id] = ?", ROW, deviceId.toString()).stream().findFirst();
    }

    /**
     * Throws 409 when the cashier can't open a shift on this till: SHIFT_OPEN_ON_OTHER_TILL when they
     * have one elsewhere, SHIFT_ALREADY_OPEN when this till has one (theirs or anyone's).
     */
    public void refuseOpen(long cashierId, Till till) {
        var own = ofCashier(cashierId);
        if (own.isPresent()) {
            var shift = own.get();
            if (Objects.equals(shift.deviceId(), till.deviceId())) {
                throw TillException.shiftAlreadyOpen(shift.id(), shift.ownerName(), till);
            }
            throw TillException.shiftOpenOnOtherTill(shift.till(), "Close it there before opening one here.");
        }
        onTill(till.deviceId()).ifPresent(other -> {
            throw TillException.shiftAlreadyOpen(other.id(), other.ownerName(), till);
        });
    }
}
