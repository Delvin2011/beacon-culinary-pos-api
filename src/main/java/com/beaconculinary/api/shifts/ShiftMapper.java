package com.beaconculinary.api.shifts;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ShiftMapper {
    @Mapping(target = "cashierId", source = "cashier.id")
    ShiftDto toDto(Shift shift);
}
