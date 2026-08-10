package com.beaconculinary.api.accounts;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountMapper {
    AccountDto toDto(Account account);

    AccountAdminDto toAdminDto(Account account);
}
