package com.beaconculinary.api.orders;

import com.beaconculinary.api.menu.DailyComponentStock;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "order_line_extras")
public class OrderLineExtra {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_line_id")
    private OrderLine orderLine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_component_stock_id")
    private DailyComponentStock dailyComponentStock;

    // Snapshotted at order time from DailyComponentStock.extraPrice.
    @Column(name = "price_delta")
    private BigDecimal priceDelta;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "line_total")
    private BigDecimal lineTotal;

    // Stage 2.6 — flagged (not deleted) by an EXTRAS_ONLY adjustment, so the original order
    // detail remains reconstructable.
    @Column(name = "adjusted")
    private boolean adjusted = false;
}
