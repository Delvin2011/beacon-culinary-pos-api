package com.beaconculinary.api.accounts;

import lombok.Data;

@Data
public class AccountAdminDto {
    private Long id;
    private String name;
    private String contactEmail;
    private boolean active;
}
