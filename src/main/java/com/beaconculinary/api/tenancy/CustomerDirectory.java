package com.beaconculinary.api.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * T1-1: the customer directory (multi mode only): finds a customer by host or slug and lists the active ones, from
 * the platform database.
 *
 * <p>The whole directory (a handful of rows) is read as one snapshot and used for {@code app.tenancy.directory-cache-ttl}
 * (default 30 s), so a lookup never waits for the database. A change made by the operator command (another process)
 * shows within that time; code in this process that changes customers calls {@link #refresh()}. If reading fails, the
 * last good snapshot is kept and a warning logged; the first read (at startup) must succeed.
 */
public class CustomerDirectory {
    private static final Logger log = LoggerFactory.getLogger(CustomerDirectory.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration ttl;
    private volatile Snapshot snapshot;

    public CustomerDirectory(JdbcTemplate jdbc, Clock clock, Duration ttl) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ttl = ttl;
        this.snapshot = load();
        var all = snapshot.customers().size();
        var active = snapshot.customers().stream().filter(Customer::isActive).count();
        log.atInfo().addKeyValue("customers", all).addKeyValue("activeCustomers", active)
                .log("tenancy multi: customer directory loaded, {} customer(s), {} active", all, active);
    }

    /** The customer this host belongs to: case-insensitive, port and trailing dot ignored. Any status. */
    public Optional<Customer> findByHost(String host) {
        return HostNames.normalise(host).map(normalised -> current().byHost().get(normalised));
    }

    /** The customer with this slug (case-insensitive). Any status. */
    public Optional<Customer> findBySlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current().bySlug().get(slug.trim().toLowerCase(Locale.ROOT)));
    }

    /** ACTIVE customers, by slug. */
    public List<Customer> listActive() {
        return current().customers().stream().filter(Customer::isActive).toList();
    }

    /** Every customer, by slug, with its status. */
    public List<Customer> listAll() {
        return current().customers();
    }

    /** Reads the platform database now (after a change made in this process). */
    public void refresh() {
        snapshot = load();
    }

    private Snapshot current() {
        var held = snapshot;
        if (Duration.between(held.loadedAt(), clock.instant()).compareTo(ttl) < 0) {
            return held;
        }
        synchronized (this) {
            if (snapshot != held) {
                return snapshot;
            }
            try {
                snapshot = load();
            } catch (RuntimeException e) {
                // Keep serving the last good snapshot; try again after another TTL.
                log.warn("Customer directory: reading the platform database failed; using the snapshot from {}", held.loadedAt(), e);
                snapshot = new Snapshot(clock.instant(), held.customers(), held.byHost(), held.bySlug());
            }
            return snapshot;
        }
    }

    private Snapshot load() {
        var loadedAt = clock.instant();
        var hosts = new HashMap<Long, List<String>>();
        jdbc.query("SELECT tenant_id, host FROM tenant_hosts ORDER BY tenant_id, is_primary DESC, host", rs -> {
            hosts.computeIfAbsent(rs.getLong("tenant_id"), id -> new ArrayList<>()).add(rs.getString("host"));
        });
        var databases = new HashMap<Long, Customer.DatabaseLocation>();
        jdbc.query("SELECT tenant_id, server_host, server_port, database_name, connection_options, login_secret_name, "
                + "password_secret_name, built_from_baseline, expected_collation, last_migrated_version, "
                + "migration_login_secret_name, migration_password_secret_name FROM tenant_databases", rs -> {
            databases.put(rs.getLong("tenant_id"), new Customer.DatabaseLocation(rs.getString("server_host"),
                    rs.getInt("server_port"), rs.getString("database_name"), rs.getString("connection_options"),
                    rs.getString("login_secret_name"), rs.getString("password_secret_name"),
                    rs.getBoolean("built_from_baseline"), rs.getString("expected_collation"),
                    rs.getString("last_migrated_version"), rs.getString("migration_login_secret_name"),
                    rs.getString("migration_password_secret_name")));
        });
        var customers = new ArrayList<Customer>();
        jdbc.query("SELECT id, slug, display_name, status, time_zone FROM tenants ORDER BY slug", rs -> {
            var id = rs.getLong("id");
            customers.add(new Customer(id, rs.getString("slug"), rs.getString("display_name"),
                    CustomerStatus.valueOf(rs.getString("status")), hosts.getOrDefault(id, List.of()), databases.get(id),
                    rs.getString("time_zone")));
        });
        customers.sort(Comparator.comparing(Customer::slug));
        var byHost = new HashMap<String, Customer>();
        var bySlug = new LinkedHashMap<String, Customer>();
        for (var customer : customers) {
            bySlug.put(customer.slug(), customer);
            for (var host : customer.hosts()) {
                byHost.put(host, customer);
            }
        }
        return new Snapshot(loadedAt, List.copyOf(customers), Map.copyOf(byHost), Map.copyOf(bySlug));
    }

    private record Snapshot(Instant loadedAt, List<Customer> customers, Map<String, Customer> byHost,
                            Map<String, Customer> bySlug) {
    }
}
