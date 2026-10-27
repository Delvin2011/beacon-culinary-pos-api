package com.beaconculinary.api.accounts;

import lombok.Data;

/** The till picker's shape (GET /accounts): enough to select an account and contact its holder. */
@Data
public class AccountDto {
    private Long id;
    private String accountNumber;
    private String name;
    private String department;
    private String contactNumber;
    private String contactEmail;
}
