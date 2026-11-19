package com.beaconculinary.api.common.locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** T0-2: the site settings and the SAST guard at startup, in a context holding only SiteLocaleConfig. */
class SiteLocaleStartupTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(SiteLocaleConfig.class);

    @Test
    void defaults_start() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var locale = context.getBean(SiteLocale.class);
            assertThat(locale.zone()).isEqualTo(ZoneId.of("Africa/Johannesburg"));
            assertThat(locale.currencyCode()).isEqualTo("ZAR");
            assertThat(locale.currencySymbol()).isEqualTo("R");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Africa/Johannesburg", "Africa/Maseru", "Africa/Harare", "Etc/GMT-2"})
    void sastZones_start(String zone) {
        runner.withPropertyValues("site.time-zone=" + zone)
                .run(context -> assertThat(context.getBean(SiteLocale.class).zone().getId()).isEqualTo(zone));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Europe/London", "Africa/Cairo", "Africa/Nairobi", "UTC", "Etc/GMT+2"})
    void nonSastZone_refusesToStart_namingTheViews(String zone) {
        runner.withPropertyValues("site.time-zone=" + zone).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("site.time-zone '" + zone + "'")
                    .hasMessageContaining("SQL report views (V85, V87, V91, V92, V111)")
                    .hasMessageContaining("South Africa Standard Time");
        });
    }

    @Test
    void invalidSettings_refuseToStart() {
        runner.withPropertyValues("site.time-zone=Mars/Olympus").run(context ->
                assertThat(context.getStartupFailure()).hasStackTraceContaining("site.time-zone 'Mars/Olympus'"));
        runner.withPropertyValues("site.currency-code=XYZ").run(context ->
                assertThat(context.getStartupFailure()).hasStackTraceContaining("site.currency-code 'XYZ'"));
        runner.withPropertyValues("site.currency-symbol= ").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void settings_overrideDefaults() {
        runner.withPropertyValues("site.currency-symbol=ZAR").run(context ->
                assertThat(context.getBean(SiteLocale.class).currencySymbol()).isEqualTo("ZAR"));
    }
}
