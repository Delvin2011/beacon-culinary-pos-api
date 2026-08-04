package com.beaconculinary.api.orders;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusEventRepository extends JpaRepository<OrderStatusEvent, Long> {
}
