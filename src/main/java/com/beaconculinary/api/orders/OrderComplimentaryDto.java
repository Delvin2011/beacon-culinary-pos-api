package com.beaconculinary.api.orders;

import com.beaconculinary.api.users.UserRefDto;

/** The complimentary details on an order response: why it was given and which admin approved it. */
public record OrderComplimentaryDto(
        ComplimentaryReasonCode reasonCode,
        String note,
        UserRefDto authorizedBy) {
}
