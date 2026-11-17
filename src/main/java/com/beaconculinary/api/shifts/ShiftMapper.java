package com.beaconculinary.api.shifts;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ShiftMapper {
    @Mapping(target = "cashierId", source = "cashier.id")
    @Mapping(target = "varianceAuthorizedById", source = "varianceAuthorizedBy.id")
    @Mapping(target = "tillName", expression = "java(com.beaconculinary.api.tills.Till.displayName(shift.getDeviceId(), shift.getTillName()))")
    ShiftDto toDto(Shift shift);
}
