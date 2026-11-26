package com.beaconculinary.api.tenancy.routing;

import com.beaconculinary.api.tenancy.CustomerDirectory;
import com.beaconculinary.api.tenancy.secrets.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * T1-2: the data layer in multi-customer mode (docs/2026-11-24-routing-data-source.md). Nothing here exists in single
 * mode, which keeps Spring Boot's own data source, Flyway run and Hibernate set-up exactly as before.
 *
 * <ul>
 *   <li>The application's {@code dataSource} is the {@link CustomerRoutingDataSource} (Spring Boot's Hikari pool backs
 *       off): every connection goes to the current customer's database, none without a current customer.</li>
 *   <li>Hibernate reads no database metadata at start-up ({@code hibernate.boot.allow_jdbc_metadata_access=false}):
 *       there is no customer at start-up, so it can't connect. The dialect is the explicit one in application.yaml;
 *       the version it assumes is {@code app.tenancy.database-major-version} (12: what Azure SQL Database reports).</li>
 *   <li>Spring Boot's Flyway run at start-up does nothing: customer databases are updated by the operator's migration
 *       command before the app starts (T1-4); a customer whose database is behind isn't served ({@link CustomerPools}).</li>
 *   <li>Idle and unserved customers' pools are closed by a sweep every minute.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.tenancy.mode", havingValue = "multi")
@EnableConfigurationProperties(CustomerPoolProperties.class)
public class MultiCustomerDataConfig {
    private static final Logger log = LoggerFactory.getLogger(MultiCustomerDataConfig.class);

    @Bean(destroyMethod = "close")
    public CustomerPools customerPools(CustomerDirectory directory, SecretResolver secrets, CustomerPoolProperties properties,
                                       Clock clock, ObjectProvider<FirstConnectionListener> listeners) {
        // The listeners are looked up at a pool's first connection: some of them use the data source themselves.
        return new CustomerPools(directory, secrets, properties, clock, () -> listeners.orderedStream().toList());
    }

    /** The application's only data source in multi mode. Its pools are closed with {@link CustomerPools}. */
    @Bean(name = "dataSource", destroyMethod = "")
    public DataSource dataSource(CustomerPools pools) {
        return new CustomerRoutingDataSource(pools);
    }

    @Bean
    public HibernatePropertiesCustomizer multiCustomerHibernate(
            @Value("${app.tenancy.database-major-version:12}") int databaseMajorVersion) {
        return properties -> {
            properties.put("hibernate.boot.allow_jdbc_metadata_access", false);
            properties.put("jakarta.persistence.database-product-name", "Microsoft SQL Server");
            properties.put("jakarta.persistence.database-major-version", databaseMajorVersion);
            properties.put("jakarta.persistence.database-minor-version", 0);
        };
    }

    @Bean
    public FlywayMigrationStrategy customerMigrationsNotAtStartup() {
        return flyway -> log.info("tenancy multi: customer database migrations are not run at start-up "
                + "(the operator's migration command updates them; a customer whose database is behind isn't served)");
    }

    @Bean
    public CustomerPoolSweeper customerPoolSweeper(CustomerPools pools) {
        return new CustomerPoolSweeper(pools);
    }

    /** Closes idle, unserved and moved customers' pools every minute. */
    public static class CustomerPoolSweeper {
        private final CustomerPools pools;

        CustomerPoolSweeper(CustomerPools pools) {
            this.pools = pools;
        }

        @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
        public void sweep() {
            try {
                pools.sweep();
            } catch (RuntimeException e) {
                log.warn("tenancy multi: the customer pool sweep failed", e);
            }
        }
    }
}
