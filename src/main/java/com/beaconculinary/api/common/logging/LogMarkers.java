package com.beaconculinary.api.common.logging;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * T0-1: SLF4J markers for lines an alert matches on. In production (Logstash JSON) a marker is written
 * as {@code "tags":["LOGIN_PROTECTION_ALERT"]}, so an Azure alert can match the tag exactly. The same
 * name stays as the first word of the message, so text searches keep working.
 */
public final class LogMarkers {
    public static final Marker LOGIN_PROTECTION_ALERT = MarkerFactory.getMarker("LOGIN_PROTECTION_ALERT");
    public static final Marker DEVICE_UNBOUND_TILL_REQUEST = MarkerFactory.getMarker("DEVICE_UNBOUND_TILL_REQUEST");

    private LogMarkers() {
    }
}
