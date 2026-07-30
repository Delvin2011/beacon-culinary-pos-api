package com.beaconculinary.api.users;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter //so that Spring can read data from these fields and create JSONS
public class UserDto {
    private Long id;
    private String name;
    private String email;
}
