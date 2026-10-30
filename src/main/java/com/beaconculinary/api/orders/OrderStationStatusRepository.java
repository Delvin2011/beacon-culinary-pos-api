package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OrderStationStatusRepository extends JpaRepository<OrderStationStatus, OrderStationStatus.Key> {
    /** A station display's work for a day: its rows in the given statuses, on orders still in the
     * kitchen/bar flow (not collected, voided or refunded), oldest first. */
    @Query("SELECT s FROM OrderStationStatus s JOIN FETCH s.order o "
            + "WHERE s.station = :station AND s.status IN :statuses AND o.orderDate = :date "
            + "AND o.status IN (com.beaconculinary.api.orders.OrderStatus.PENDING, "
            + "com.beaconculinary.api.orders.OrderStatus.IN_PROGRESS, com.beaconculinary.api.orders.OrderStatus.DONE) "
            + "ORDER BY o.createdAt, o.id")
    List<OrderStationStatus> findWork(@Param("station") Station station, @Param("statuses") List<OrderStatus> statuses,
                                      @Param("date") LocalDate date);
}
