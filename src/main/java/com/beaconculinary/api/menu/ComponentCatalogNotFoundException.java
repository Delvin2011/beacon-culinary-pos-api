package com.beaconculinary.api.menu;

public class ComponentCatalogNotFoundException extends RuntimeException {
    public ComponentCatalogNotFoundException() {
        super("Component catalog entry not found.");
    }
}
