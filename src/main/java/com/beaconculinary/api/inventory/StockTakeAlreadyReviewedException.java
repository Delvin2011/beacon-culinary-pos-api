package com.beaconculinary.api.inventory;

/** A stock take already {@code APPROVED} or {@code REJECTED} can't be reviewed again. */
public class StockTakeAlreadyReviewedException extends RuntimeException {
    public StockTakeAlreadyReviewedException() {
        super("Stock take has already been reviewed.");
    }
}
