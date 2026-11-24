package com.beaconculinary.api.tenancy;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashSet;

/**
 * T1-1: tenancy settings and, in multi mode only, the platform database. In single mode (the default) nothing here
 * connects anywhere: no platform pool, no platform migrations.
 */
@Configuration
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyConfig {
    static final String PLATFORM_DATABASE_BEAN = "platformDatabase";

    @Bean(name = PLATFORM_DATABASE_BEAN)
    @ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
    public PlatformDatabase platformDatabase(TenancyProperties properties) {
        return new PlatformDatabase(properties);
    }

    /** Multi mode: the customer directory, read from the platform database (snapshot for directory-cache-ttl). */
    @Bean
    @ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
    public CustomerDirectory customerDirectory(PlatformDatabase platformDatabase, Clock clock, TenancyProperties properties) {
        return new CustomerDirectory(platformDatabase.jdbc(), clock, properties.getDirectoryCacheTtl());
    }

    /**
     * Multi mode: every DataSource bean (the customer data source) depends on the platform database, so its
     * migrations run before anything touches a customer database (the customer Flyway, JPA, the jobs).
     */
    @Bean
    @ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
    public static BeanFactoryPostProcessor dataSourcesAfterPlatformDatabase() {
        return beanFactory -> {
            for (var name : beanFactory.getBeanNamesForType(DataSource.class, true, false)) {
                var definition = beanFactory.getBeanDefinition(name);
                var dependsOn = new LinkedHashSet<String>();
                if (definition.getDependsOn() != null) {
                    dependsOn.addAll(Arrays.asList(definition.getDependsOn()));
                }
                dependsOn.add(PLATFORM_DATABASE_BEAN);
                definition.setDependsOn(dependsOn.toArray(String[]::new));
            }
        };
    }
}
