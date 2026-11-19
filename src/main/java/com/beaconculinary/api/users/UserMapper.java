package com.beaconculinary.api.users;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring") // so that spring can create associated beans at runtime
public interface UserMapper {
    UserDto toDto(User user);
    CashierSummaryDto toCashierSummaryDto(User user);
}
