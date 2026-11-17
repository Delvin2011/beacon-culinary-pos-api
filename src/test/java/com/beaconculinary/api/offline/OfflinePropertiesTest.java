package com.beaconculinary.api.offline;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** O3: pos.offline.* startup validation (contract section 2). */
class OfflinePropertiesTest {
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void theDefaults_areValid_andOfflineIsOff() {
        var properties = new OfflineProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMaxOfflineHours()).isEqualTo(6);
        assertThat(properties.getWarnOfflineHours()).isEqualTo(5);
        assertThat(properties.isAccountsEnabled()).isTrue();
        assertThat(properties.getAccountCap()).isEqualByComparingTo("150.00");
        assertThat(properties.getGrantMaxHours()).isEqualTo(18);
        assertThat(properties.getStatusStaleMinutes()).isEqualTo(10);
    }

    @Test
    void warnMustBeBeforeMax() {
        var properties = new OfflineProperties();
        properties.setWarnOfflineHours(6);

        assertThat(messages(properties)).containsExactly("pos.offline.warn-offline-hours must be less than pos.offline.max-offline-hours");
    }

    @Test
    void aGrantMustLastAtLeastTheOfflineLimit() {
        var properties = new OfflineProperties();
        properties.setGrantMaxHours(5);

        assertThat(messages(properties)).containsExactly("pos.offline.grant-max-hours must be at least pos.offline.max-offline-hours");
    }

    @Test
    void enabled_needsASigningKeyOfAtLeast32Bytes() {
        var properties = new OfflineProperties();
        properties.setEnabled(true);
        var expected = "pos.offline.grant-signing-key must be Base64 of at least 32 bytes when pos.offline.enabled is true";

        assertThat(messages(properties)).containsExactly(expected);
        properties.setGrantSigningKey(Base64.getEncoder().encodeToString(new byte[31]));
        assertThat(messages(properties)).containsExactly(expected);
        properties.setGrantSigningKey("not base64 !!");
        assertThat(messages(properties)).containsExactly(expected);
        properties.setGrantSigningKey(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void outOfRangeValues_areRefused() {
        var properties = new OfflineProperties();
        properties.setAccountCap(new BigDecimal("-0.01"));
        properties.setStatusStaleMinutes(1);
        properties.setGrantKeyId(" ");

        assertThat(validator.validate(properties)).hasSize(3);
    }

    @Test
    void toString_neverShowsTheSigningKey() {
        var properties = new OfflineProperties();
        var key = Base64.getEncoder().encodeToString("a-secret-that-must-not-be-printed!".getBytes());
        properties.setGrantSigningKey(key);

        assertThat(properties.toString()).doesNotContain(key).contains("grantKeyId=k1");
    }

    private static java.util.List<String> messages(OfflineProperties properties) {
        return validator.validate(properties).stream().map(v -> v.getMessage()).toList();
    }
}
