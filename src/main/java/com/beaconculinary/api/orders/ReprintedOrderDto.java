package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.UserRefDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/** Reports R3: the order's normal receipt payload plus which copy this is. The receipt must be
 * printed marked "COPY n". */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReprintedOrderDto extends OrderDto {
    private Reprint reprint;

    public record Reprint(int copyNumber, UserRefDto reprintedBy, Instant reprintedAt) {
    }
}
