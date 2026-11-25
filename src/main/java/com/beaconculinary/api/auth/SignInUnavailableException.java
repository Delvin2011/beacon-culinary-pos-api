package com.beaconculinary.api.auth;

import com.beaconculinary.api.tenancy.context.NoCurrentCustomerException;
import com.beaconculinary.api.tenancy.routing.CustomerNotServedException;
import com.beaconculinary.api.tenancy.routing.UnknownCustomerException;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.TransactionException;

/**
 * T1-3: password or PIN sign-in couldn't reach the database. Answered 503 {@code DATABASE_UNAVAILABLE}
 * ({@link SignInUnavailableExceptionHandler}), never as a wrong credential, and never recorded as a failed attempt.
 */
public class SignInUnavailableException extends RuntimeException {
    public static final String CODE = "DATABASE_UNAVAILABLE";

    SignInUnavailableException(Throwable cause) {
        super("Sign-in couldn't reach the database", cause);
    }

    /** Whether this exception (from a sign-in) is the database failing rather than a credential problem. */
    static boolean isDatabaseFailure(RuntimeException e) {
        return e instanceof DataAccessException || e instanceof TransactionException || e instanceof PersistenceException
                || e instanceof InternalAuthenticationServiceException;
    }

    /**
     * What to throw for a database failure during sign-in: T1-2's tenancy exceptions as they are (the customer filter
     * answers them by the contract, e.g. a customer suspended during the request), otherwise this exception.
     */
    static RuntimeException of(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof CustomerNotServedException || cause instanceof UnknownCustomerException
                    || cause instanceof NoCurrentCustomerException) {
                return (RuntimeException) cause;
            }
        }
        return new SignInUnavailableException(e);
    }
}
