package com.beaconculinary.api.auth.protection;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoginProtectionPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaults_areValid_andOffWhenUnset() {
        var properties = new LoginProtectionProperties();
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getMode()).isEqualTo(LoginProtectionMode.OFF);
        assertThat(properties.getRetentionDays()).isEqualTo(90);
        assertThat(properties.getTrustedProxies()).isEmpty();
    }

    @Test
    void enforce_isAccepted_andBurstDefaultsSuitOneCanteen() {
        var properties = new LoginProtectionProperties();
        properties.setMode(LoginProtectionMode.ENFORCE);
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.isEnforcing()).isTrue();
        assertThat(properties.getBurst().getCapacity()).isEqualTo(60);
        assertThat(properties.getBurst().getRefillTokens()).isEqualTo(60);
        assertThat(properties.getBurst().getRefillPeriod()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getBurst().getMaxTrackedClients()).isEqualTo(10_000);
    }

    @Test
    void burst_settingsAreValidated() {
        var properties = new LoginProtectionProperties();
        properties.getBurst().setCapacity(0);
        assertThat(validator.validate(properties)).isNotEmpty();

        var zeroRefill = new LoginProtectionProperties();
        zeroRefill.getBurst().setRefillPeriod(Duration.ZERO);
        assertThat(messages(zeroRefill)).anyMatch(m -> m.contains("positive"));
    }

    @Test
    void trustedProxies_mustBeIpsOrCidrs() {
        var properties = new LoginProtectionProperties();
        properties.setTrustedProxies(List.of("10.0.0.0/8", "ingress.internal"));
        assertThat(messages(properties)).anyMatch(m -> m.contains("trusted-proxies"));
    }

    @Test
    void lockDurations_needExactlyTwo_andPositiveDurations() {
        var properties = new LoginProtectionProperties();
        properties.setLockDurations(List.of(Duration.ofMinutes(15)));
        assertThat(messages(properties)).anyMatch(m -> m.contains("exactly two"));

        properties.setLockDurations(List.of(Duration.ofMinutes(15), Duration.ZERO));
        assertThat(messages(properties)).anyMatch(m -> m.contains("positive"));
    }

    @Test
    void thresholds_needAtLeastOneFailure() {
        var properties = new LoginProtectionProperties();
        properties.getPinPair().setMaxFailures(0);
        assertThat(validator.validate(properties)).isNotEmpty();
    }

    private List<String> messages(LoginProtectionProperties properties) {
        return validator.validate(properties).stream().map(v -> v.getMessage()).toList();
    }
}
