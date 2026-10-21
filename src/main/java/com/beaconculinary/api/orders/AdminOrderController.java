package com.beaconculinary.api.orders;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports R3: admin-only order actions (ADMIN via AdminSecurityRules' /admin/** rule). */
@AllArgsConstructor
@RestController
@RequestMapping("/admin/orders")
public class AdminOrderController {
    private final ReceiptReprintService receiptReprintService;

    /** The order's receipt payload plus reprint { copyNumber, reprintedBy, reprintedAt }. */
    @PostMapping("/{id}/reprint-receipt")
    public ReprintedOrderDto reprintReceipt(@PathVariable long id) {
        return receiptReprintService.reprint(id);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }
}
