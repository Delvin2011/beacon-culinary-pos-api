package com.beaconculinary.api.orders;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A sale order needs at least one payment; a complimentary order has none (OrderService refuses
 * any it is given). Reported on the payments field, exactly as the old @NotEmpty did:
 * 400 { "payments": "payments cannot be empty" }.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PaymentsRequiredForSale.Validator.class)
public @interface PaymentsRequiredForSale {
    String message() default "payments cannot be empty";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PaymentsRequiredForSale, CreateOrderRequest> {
        @Override
        public boolean isValid(CreateOrderRequest request, ConstraintValidatorContext context) {
            if (request == null || request.getComplimentary() != null
                    || (request.getPayments() != null && !request.getPayments().isEmpty())) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("payments").addConstraintViolation();
            return false;
        }
    }
}
