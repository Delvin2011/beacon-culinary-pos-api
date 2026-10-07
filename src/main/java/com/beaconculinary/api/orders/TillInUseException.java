package com.beaconculinary.api.orders;

/** POS Oversight B1: a CASHIER tried to sell while another user's shift holds the till. */
public class TillInUseException extends RuntimeException {
    public TillInUseException(String ownerName) {
        super("Till in use by " + ownerName + ".");
    }
}
