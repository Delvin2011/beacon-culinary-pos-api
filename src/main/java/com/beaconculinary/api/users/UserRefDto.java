package com.beaconculinary.api.users;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A compact reference to a user inside another resource (shift owner, who rang an order up,
 * who authorized an adjustment). role is omitted where the spec shows only id and name. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserRefDto(Long id, String name, Role role) {
    public static UserRefDto withRole(User user) {
        return user == null ? null : new UserRefDto(user.getId(), user.getName(), user.getRole());
    }

    public static UserRefDto of(User user) {
        return user == null ? null : new UserRefDto(user.getId(), user.getName(), null);
    }
}
