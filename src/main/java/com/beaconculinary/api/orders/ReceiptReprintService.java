package com.beaconculinary.api.orders;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.common.UtcTimestamps;
import com.beaconculinary.api.users.UserRefDto;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Reports R3: reprints an order's receipt for an admin. Each reprint is logged in
 * receipt_reprints with the next copy number (1, 2, …); the order itself is never touched, so
 * its status and one-time collection are unaffected.
 */
@Service
@AllArgsConstructor
public class ReceiptReprintService {
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final AuthService authService;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public ReprintedOrderDto reprint(long orderId) {
        var order = orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new);
        var user = authService.getCurrentUser();
        // UPDLOCK/HOLDLOCK serialises concurrent reprints of the same order; the unique
        // (order_id, copy_number) constraint backs it up.
        var copy = jdbc.queryForObject("SELECT COALESCE(MAX(copy_number), 0) + 1 FROM receipt_reprints WITH (UPDLOCK, HOLDLOCK) "
                + "WHERE order_id = ?", Integer.class, orderId);
        var now = clock.instant();
        jdbc.update("INSERT INTO receipt_reprints (order_id, copy_number, reprinted_by, reprinted_at) VALUES (?, ?, ?, ?)",
                orderId, copy, user.getId(), java.sql.Timestamp.valueOf(UtcTimestamps.toUtc(now)));

        var dto = new ReprintedOrderDto();
        BeanUtils.copyProperties(orderMapper.toDto(order), dto);
        dto.setReprint(new ReprintedOrderDto.Reprint(copy, UserRefDto.of(user), now));
        return dto;
    }
}
