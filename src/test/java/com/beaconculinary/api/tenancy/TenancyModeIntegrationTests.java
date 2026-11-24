package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.BeaconCulinaryApiApplication;
import com.beaconculinary.api.support.baseline.ThrowawayDatabase;
import com.beaconculinary.api.tenancy.routing.CustomerPools;
import com.beaconculinary.api.tenancy.routing.CustomerRoutingDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-1: app.tenancy.mode. Single (the default, the suite's): no platform database bean, nothing connects to one.
 * Multi: the platform migrations run at startup on their own database, before the customer data source is made;
 * incomplete settings stop startup with a message that names settings, never values. The multi-mode applications are
 * started here as separate contexts, against a throwaway platform database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
class TenancyModeIntegrationTests {
    /** Never a real password: it only has to be recognisable in captured output. */
    private static final String SENTINEL = "Sentinel-Value-7f3a";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private TenancyProperties properties;

    @Test
    void singleMode_isTheDefault_andHasNoPlatformDatabase() {
        assertThat(properties.getMode()).isEqualTo(TenancyMode.SINGLE);
        assertThat(context.getBeanNamesForType(PlatformDatabase.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CustomerDirectory.class)).isEmpty();
        assertThat(context.containsBean(TenancyConfig.PLATFORM_DATABASE_BEAN)).isFalse();
    }

    @Test
    void multiMode_migratesThePlatformDatabase_beforeTheCustomerDataSource(CapturedOutput output) throws Exception {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        Assumptions.assumeTrue(ThrowawayDatabase.canCreate(url, username, password), "the test login can't create databases");
        try (var platform = ThrowawayDatabase.create(url, username, password, "TenancyMulti", "SQL_Latin1_General_CP1_CI_AS");
             var app = start(Map.of(
                     "app.tenancy.mode", "multi",
                     "app.tenancy.platform.url", platform.url(),
                     "app.tenancy.platform.username", platform.username(),
                     "app.tenancy.platform.password", platform.password()))) {
            var database = app.getBean(PlatformDatabase.class);
            assertThat(database.version()).isEqualTo("1");
            assertThat(database.jdbc().queryForObject("SELECT COUNT(*) FROM tenants", Integer.class)).isZero();
            assertThat(app.getBean(CustomerDirectory.class).listAll()).isEmpty();
            assertThat(app.getBean(TenancyProperties.class).isMulti()).isTrue();
            // The customer data source waits for the platform database.
            assertThat(app.getBeanFactory().getBeanDefinition("dataSource").getDependsOn())
                    .contains(TenancyConfig.PLATFORM_DATABASE_BEAN);
            var log = output.getAll();
            var platformLine = log.indexOf("tenancy multi: platform database at version 1");
            assertThat(platformLine).isPositive();
            // T1-2: the data source routes to the current customer's database, and nothing connects to one at
            // start-up (no Spring Boot Hikari pool, no customer pool).
            assertThat(app.getBean("dataSource")).isInstanceOf(CustomerRoutingDataSource.class);
            assertThat(app.getBean(CustomerPools.class).poolsCreated()).isZero();
            assertThat(log).doesNotContain("HikariPool-");
            assertThat(log).contains("tenancy multi: customer directory loaded, 0 customer(s), 0 active");
        }
    }

    @Test
    void multiMode_withIncompleteSettings_refusesToStart_namingTheSettingsNotTheValues(CapturedOutput output) {
        assertThatThrownBy(() -> start(Map.of("app.tenancy.mode", "multi"))).rootCause()
                .hasMessageContaining("app.tenancy.platform.url (APP_TENANCY_PLATFORM_URL) is not set")
                .hasMessageContaining("app.tenancy.platform.username")
                .hasMessageContaining("app.tenancy.platform.password");

        assertThatThrownBy(() -> start(Map.of(
                "app.tenancy.mode", "multi",
                "app.tenancy.platform.url", "jdbc:sqlserver://example.invalid:1433;databaseName=pos_platform;password=" + SENTINEL,
                "app.tenancy.platform.username", "platform",
                "app.tenancy.platform.password", SENTINEL))).rootCause()
                .hasMessageContaining("must not contain a user or password")
                .message().doesNotContain(SENTINEL);
        assertThat(output.getAll().contains(SENTINEL)).as("the password appears in the output").isFalse();
    }

    @Test
    void anUnknownMode_refusesToStart() {
        assertThatThrownBy(() -> start(Map.of("app.tenancy.mode", "both")))
                .hasStackTraceContaining("app.tenancy.mode");
    }

    /** Starts the application (no web server) with these settings on top of the suite's own. */
    private ConfigurableApplicationContext start(Map<String, Object> settings) {
        var values = new HashMap<String, Object>(settings);
        // The suite's database and settings, so the application otherwise starts as in every other test.
        for (var key : new String[]{"spring.datasource.url", "spring.datasource.username", "spring.datasource.password"}) {
            values.put(key, environment.getRequiredProperty(key));
        }
        return new SpringApplicationBuilder(BeaconCulinaryApiApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t1-1-test", values)))
                .run();
    }
}
