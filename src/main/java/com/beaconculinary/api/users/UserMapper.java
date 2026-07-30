package com.beaconculinary.api.users;

import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring") // so that spring can create associated beans at runtime
public interface UserMapper {
    UserDto toDto(User user);
    CashierSummaryDto toCashierSummaryDto(User user);
    User toEntity(RegisterUserRequest request);
    void update(UpdateUserRequest request, @MappingTarget User user);
}
