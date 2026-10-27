package com.beaconculinary.api.accounts;

import lombok.Data;

@Data
public class AccountAdminDto {
    private Long id;
    private String accountNumber;
    private String name;
    private String department;
    private String contactNumber;
    private String contactEmail;
    private boolean active;
}
