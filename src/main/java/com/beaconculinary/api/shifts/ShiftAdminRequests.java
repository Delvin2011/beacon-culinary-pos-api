package com.beaconculinary.api.shifts;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** O2 request bodies for /admin/shifts/{id}/force-close and /admin/shifts/{id}/count. */
public final class ShiftAdminRequests {
    private ShiftAdminRequests() {
    }

    @Data
    public static class ForceClose {
        /** Omitted when the drawer can't be counted now: the shift is then UNCOUNTED. */
        @DecimalMin(value = "0.00", message = "countedCash cannot be negative")
        private BigDecimal countedCash;

        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "reason must be 255 characters or fewer")
        private String reason;

        /** Required when countedCash differs from expected cash. */
        private ShiftVarianceReasonCode varianceReasonCode;
    }

    @Data
    public static class LateCount {
        @NotNull(message = "countedCash is required")
        @DecimalMin(value = "0.00", message = "countedCash cannot be negative")
        private BigDecimal countedCash;

        /** Required when countedCash differs from the expected cash snapshotted at close. */
        private ShiftVarianceReasonCode varianceReasonCode;

        @Size(max = 255, message = "note must be 255 characters or fewer")
        private String note;
    }
}
