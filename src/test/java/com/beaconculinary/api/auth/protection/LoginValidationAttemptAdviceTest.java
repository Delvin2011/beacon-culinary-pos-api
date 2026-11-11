package com.beaconculinary.api.auth.protection;

import com.beaconculinary.api.admin.AuthorizeRequest;
import com.beaconculinary.api.auth.LoginRequest;
import com.beaconculinary.api.auth.PinLoginRequest;
import com.beaconculinary.api.common.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The advice records only LoginRequest, PinLoginRequest and AuthorizeRequest, and always returns
 * what GlobalExceptionHandler returns. This also covers any @Valid endpoint added to AdminController
 * later: its request type is not one of the three, so nothing is recorded and the response is unchanged.
 */
class LoginValidationAttemptAdviceTest {
    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);
    private final GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();
    private final LoginValidationAttemptAdvice advice = new LoginValidationAttemptAdvice(loginAttempts, globalExceptionHandler);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void otherRequestType_recordsNothing_andReturnsTheGlobalHandlersResponse() throws Exception {
        var exception = invalid(new SomeOtherRequest(), "value", "value is required");

        var response = advice.handleValidationErrors(exception);

        verifyNoInteractions(loginAttempts);
        assertThat(response).isEqualTo(globalExceptionHandler.handleValidationErrors(exception));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsExactlyEntriesOf(java.util.Map.of("value", "value is required"));
    }

    @Test
    void loginRequest_recordsPasswordValidation_withTheNormalisedEmail() throws Exception {
        var request = new LoginRequest();
        request.setEmail("  Someone@Example.TEST ");
        var exception = invalid(request, "password", "Password is required");

        var response = advice.handleValidationErrors(exception);

        verify(loginAttempts).recordValidationFailure(LoginMethod.PASSWORD, "someone@example.test", null);
        verifyNoMoreInteractions(loginAttempts);
        assertThat(response).isEqualTo(globalExceptionHandler.handleValidationErrors(exception));
    }

    @Test
    void pinLoginRequest_recordsPinValidation_withTheCashierId() throws Exception {
        var request = new PinLoginRequest();
        request.setCashierId(42L);
        var exception = invalid(request, "pin", "PIN must be 6 digits");

        var response = advice.handleValidationErrors(exception);

        verify(loginAttempts).recordValidationFailure(LoginMethod.PIN, "42", null);
        verifyNoMoreInteractions(loginAttempts);
        assertThat(response).isEqualTo(globalExceptionHandler.handleValidationErrors(exception));
    }

    @Test
    void authorizeRequest_recordsManagerPinValidation_forTheCaller() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        var exception = invalid(new AuthorizeRequest(), "pin", "PIN must be 6 digits");

        var response = advice.handleValidationErrors(exception);

        verify(loginAttempts).recordValidationFailure(LoginMethod.MANAGER_PIN, "7", 7L);
        verifyNoMoreInteractions(loginAttempts);
        assertThat(response).isEqualTo(globalExceptionHandler.handleValidationErrors(exception));
    }

    private static MethodArgumentNotValidException invalid(Object target, String field, String message) throws Exception {
        var bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", field, message));
        var parameter = new MethodParameter(LoginValidationAttemptAdviceTest.class.getDeclaredMethod("handler", Object.class), 0);
        return new MethodArgumentNotValidException(parameter, bindingResult);
    }

    @SuppressWarnings("unused")
    private void handler(Object request) {
    }

    static class SomeOtherRequest {
        private String value;

        public String getValue() {
            return value;
        }
    }
}
