package com.beaconculinary.api.accounts;

import lombok.Data;

/** Lightweight shape for the till picker (GET /accounts) — just enough to select an account. */
@Data
public class AccountDto {
    private Long id;
    private String name;
}
