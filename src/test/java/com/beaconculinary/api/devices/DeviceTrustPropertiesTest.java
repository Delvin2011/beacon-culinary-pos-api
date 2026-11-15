package com.beaconculinary.api.devices;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTrustPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaults_areValid_andOff() {
        var properties = new DeviceTrustProperties();
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getMode()).isEqualTo(DeviceTrustMode.OFF);
        assertThat(properties.isOff()).isTrue();
        assertThat(properties.isMultiBrowserEnabled()).isFalse();
        assertThat(properties.isRequireSerial()).isTrue();
        assertThat(properties.getIdleExpiryDays()).isEqualTo(90);
        assertThat(properties.getCookieMaxAgeDays()).isEqualTo(365);
        assertThat(properties.getLastSeenInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getReauthMaxAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.getDevicePin().getMaxFailures()).isEqualTo(30);
        assertThat(properties.getDevicePin().getWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void multiBrowser_needsSerial() {
        var properties = new DeviceTrustProperties();
        properties.setMultiBrowserEnabled(true);
        properties.setRequireSerial(false);
        assertThat(messages(properties)).anyMatch(m -> m.contains("require-serial"));
    }

    @Test
    void durations_mustBePositive() {
        var properties = new DeviceTrustProperties();
        properties.setLastSeenInterval(Duration.ZERO);
        assertThat(messages(properties)).anyMatch(m -> m.contains("positive"));
    }

    @Test
    void environmentVariables_overrideEverySetting() {
        var env = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "SECURITY_DEVICETRUST_MODE", "required",
                "SECURITY_DEVICETRUST_MULTIBROWSERENABLED", "true",
                "SECURITY_DEVICETRUST_REQUIRESERIAL", "true",
                "SECURITY_DEVICETRUST_IDLEEXPIRYDAYS", "30",
                "SECURITY_DEVICETRUST_COOKIEMAXAGEDAYS", "200",
                "SECURITY_DEVICETRUST_LASTSEENINTERVAL", "10m",
                "SECURITY_DEVICETRUST_REAUTHMAXAGE", "15m",
                "SECURITY_DEVICETRUST_DEVICEPIN_MAXFAILURES", "12",
                "SECURITY_DEVICETRUST_DEVICEPIN_WINDOW", "20m"));
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env);
        var bound = Binder.get(environment).bind("security.device-trust", DeviceTrustProperties.class).get();

        assertThat(bound.getMode()).isEqualTo(DeviceTrustMode.REQUIRED);
        assertThat(bound.isMultiBrowserEnabled()).isTrue();
        assertThat(bound.getIdleExpiryDays()).isEqualTo(30);
        assertThat(bound.getCookieMaxAgeDays()).isEqualTo(200);
        assertThat(bound.getLastSeenInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(bound.getReauthMaxAge()).isEqualTo(Duration.ofMinutes(15));
        assertThat(bound.getDevicePin().getMaxFailures()).isEqualTo(12);
        assertThat(bound.getDevicePin().getWindow()).isEqualTo(Duration.ofMinutes(20));
    }

    /** The shipped application.yaml must bind to OFF: a bare YAML off would be read as false. */
    @Test
    void applicationYaml_bindsModeOff() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application",
                new org.springframework.core.io.ClassPathResource("application.yaml"));
        var environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        var bound = Binder.get(environment).bind("security.device-trust", DeviceTrustProperties.class).get();
        assertThat(bound.getMode()).isEqualTo(DeviceTrustMode.OFF);
        assertThat(validator.validate(bound)).isEmpty();
    }

    private List<String> messages(DeviceTrustProperties properties) {
        return validator.validate(properties).stream().map(v -> v.getMessage()).toList();
    }
}
