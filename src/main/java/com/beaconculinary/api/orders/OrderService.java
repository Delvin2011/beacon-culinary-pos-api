package com.beaconculinary.api.orders;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.menu.DailyComponentStockRepository;
import com.beaconculinary.api.menu.DailyMealOptionRepository;
import com.beaconculinary.api.shifts.ShiftRepository;
import com.beaconculinary.api.shifts.ShiftStatus;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@AllArgsConstructor
public class OrderService {
    private final ShiftRepository shiftRepository;
    private final AuthService authService;
    private final DailyMealOptionRepository dailyMealOptionRepository;
    private final DailyComponentStockRepository dailyComponentStockRepository;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final Clock clock;

    @Transactional
    public OrderDto createOrder(CreateOrderRequest request) {
        var currentUser = authService.getCurrentUser();
        var shift = shiftRepository.findFirstByCashierIdAndStatus(currentUser.getId(), ShiftStatus.OPEN)
                .orElseThrow(NoOpenShiftException::new);

        var today = LocalDate.now(clock);
        var now = LocalTime.now(clock);

        var order = new Order();
        order.setShift(shift);
        order.setCashier(currentUser);
        order.setOrderDate(today);

        var subtotal = BigDecimal.ZERO;
        var total = BigDecimal.ZERO;

        // Phase 1: validate and price every line/extra before writing anything, so an invalid
        // line anywhere in the request never triggers a partial decrement.
        for (var lineRequest : request.getLines()) {
            var option = dailyMealOptionRepository.findById(lineRequest.getDailyMealOptionId())
                    .orElseThrow(() -> new InvalidOrderRequestException("dailyMealOptionId does not exist."));

            if (!option.getOptionDate().equals(today)) {
                throw new InvalidOrderRequestException("Meal option is not available today.");
            }
            if (!option.getMealPeriod().isActiveAt(now)) {
                throw new InvalidOrderRequestException("Meal option's meal period is not currently active.");
            }

            var line = new OrderLine();
            line.setOrder(order);
            line.setDailyMealOption(option);
            line.setUnitPrice(option.getPrice());
            line.setQuantity(lineRequest.getQuantity());
            var lineTotal = option.getPrice().multiply(BigDecimal.valueOf(lineRequest.getQuantity()));
            line.setLineTotal(lineTotal);
            subtotal = subtotal.add(lineTotal);
            total = total.add(lineTotal);

            for (var extraRequest : lineRequest.getExtras()) {
                var stock = dailyComponentStockRepository.findById(extraRequest.getDailyComponentStockId())
                        .orElseThrow(() -> new InvalidOrderRequestException("dailyComponentStockId does not exist."));

                if (!stock.getOptionDate().equals(today)) {
                    throw new InvalidOrderRequestException("Extra component has no stock declared for today.");
                }
                // Cross-dish extras are fine — the only requirement is the same meal period as
                // the line it's attached to, not the same dish composition.
                if (!stock.getMealPeriod().getId().equals(option.getMealPeriod().getId())) {
                    throw new InvalidOrderRequestException("Extra component is not stocked for this meal period.");
                }

                var extra = new OrderLineExtra();
                extra.setOrderLine(line);
                extra.setDailyComponentStock(stock);
                extra.setPriceDelta(stock.getExtraPrice());
                extra.setQuantity(extraRequest.getQuantity());
                var extraLineTotal = stock.getExtraPrice().multiply(BigDecimal.valueOf(extraRequest.getQuantity()));
                extra.setLineTotal(extraLineTotal);
                total = total.add(extraLineTotal);

                line.getExtras().add(extra);
            }

            order.getLines().add(line);
        }

        // Phase 2: atomic, conditional decrement per line/extra. A failed decrement throws,
        // which rolls back the whole transaction — including any decrements already applied
        // earlier in this same request — so no order is ever left half-fulfilled.
        for (var line : order.getLines()) {
            var decremented = dailyMealOptionRepository.decrementPortionsRemaining(
                    line.getDailyMealOption().getId(), line.getQuantity());
            if (decremented == 0) {
                throw new InsufficientStockException(
                        "Not enough portions remaining for '" + line.getDailyMealOption().getName() + "'.");
            }

            for (var extra : line.getExtras()) {
                var decrementedExtra = dailyComponentStockRepository.decrementBufferRemaining(
                        extra.getDailyComponentStock().getId(), extra.getQuantity());
                if (decrementedExtra == 0) {
                    throw new InsufficientStockException("Not enough stock remaining for the requested extra.");
                }
            }
        }

        if (request.getAmountTendered().compareTo(total) < 0) {
            throw new InvalidOrderRequestException("amountTendered is less than the order total.");
        }
        var changeDue = request.getAmountTendered().subtract(total);

        var orderNumber = orderRepository.findMaxOrderNumberForDate(today) + 1;
        order.setOrderNumber(orderNumber);
        order.setAmountTendered(request.getAmountTendered());
        order.setChangeDue(changeDue);
        order.setSubtotal(subtotal);
        order.setTotal(total);

        orderRepository.save(order);

        return orderMapper.toDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getTodayOrders() {
        var today = LocalDate.now(clock);
        return orderRepository.findByOrderDateOrderByOrderNumberDesc(today)
                .stream().map(orderMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(Long id) {
        var order = orderRepository.findById(id).orElseThrow(OrderNotFoundException::new);
        return orderMapper.toDto(order);
    }

    @Transactional
    public OrderDto markPrintFailed(Long id) {
        var order = orderRepository.findById(id).orElseThrow(OrderNotFoundException::new);
        order.setPrintFailed(true);
        orderRepository.save(order);
        return orderMapper.toDto(order);
    }
}
