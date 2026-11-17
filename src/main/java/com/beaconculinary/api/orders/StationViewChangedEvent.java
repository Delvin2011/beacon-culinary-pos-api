package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;

import java.util.EnumMap;
import java.util.Map;

/** Internal: an order changed in a way the station displays (kitchen, bar) must show — created,
 * a station or overall status change, or extras removed. Each display listener sends its own view
 * of the order, and only when the order has that station. The public board listens to
 * OrderStatusStreamEvent instead, which fires only for overall status changes.
 *
 * <p>O1: the views are built when the event is published, inside the transaction while the order
 * is still attached; the listeners send them only after the transaction commits. */
public record StationViewChangedEvent(OrderEventType eventType, Map<Station, OrderSummaryDto> views) {
    static StationViewChangedEvent of(Order order, OrderEventType eventType, StationViews stationViews) {
        var views = new EnumMap<Station, OrderSummaryDto>(Station.class);
        for (var station : Station.values()) {
            if (station.prepares() && stationViews.has(order, station)) {
                views.put(station, stationViews.view(order, station));
            }
        }
        return new StationViewChangedEvent(eventType, views);
    }

    /** This station's view, or null when the order has no part for it. */
    public OrderSummaryDto viewFor(Station station) {
        return views.get(station);
    }
}
