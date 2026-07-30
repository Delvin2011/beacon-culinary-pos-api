package com.beaconculinary.api.users;

/** Public, pre-login shape for the POS cashier tile grid — deliberately just enough to render a tile and log in. */
public record CashierSummaryDto(Long id, String name) {
}
