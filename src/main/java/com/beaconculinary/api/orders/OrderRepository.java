package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("SELECT COALESCE(MAX(o.orderNumber), 0) FROM Order o WHERE o.orderDate = :orderDate")
    int findMaxOrderNumberForDate(@Param("orderDate") LocalDate orderDate);

    List<Order> findByOrderDateOrderByOrderNumberDesc(LocalDate orderDate);
}
