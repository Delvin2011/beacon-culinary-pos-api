package com.beaconculinary.api.accounts;

public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("Account not found.");
    }
}
