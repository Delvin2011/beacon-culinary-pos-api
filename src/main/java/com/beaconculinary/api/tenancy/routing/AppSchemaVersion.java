package com.beaconculinary.api.tenancy.routing;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;

/**
 * T1-4: the newest customer schema version this app contains: the highest versioned migration in
 * {@code db/migration} (V...) or baseline in {@code db/baseline} (B...) on its classpath. Read once. In multi-customer
 * mode a customer whose database is behind it isn't served ({@link CustomerPools}); docs/2026-11-26-customer-migrations.md.
 */
public final class AppSchemaVersion {
    private static final Pattern FILE = Pattern.compile("^[VB]([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");
    private static volatile MigrationVersion latest;

    private AppSchemaVersion() {
    }

    public static MigrationVersion latest() {
        var found = latest;
        if (found == null) {
            found = scan();
            latest = found;
        }
        return found;
    }

    /**
     * How a database's version, as {@link CustomerPools} reads it ("none ..." when nothing was applied), compares with
     * the app's: negative behind, zero the same, positive ahead.
     */
    static int compare(String databaseVersion) {
        if (databaseVersion == null || databaseVersion.startsWith("none")) {
            return -1;
        }
        return MigrationVersion.fromVersion(databaseVersion).compareTo(latest());
    }

    private static MigrationVersion scan() {
        var resolver = new PathMatchingResourcePatternResolver(AppSchemaVersion.class.getClassLoader());
        MigrationVersion highest = null;
        try {
            for (var pattern : new String[]{"classpath*:db/migration/V*__*.sql", "classpath*:db/baseline/B*__*.sql"}) {
                for (var resource : resolver.getResources(pattern)) {
                    var matcher = resource.getFilename() == null ? null : FILE.matcher(resource.getFilename());
                    if (matcher != null && matcher.matches()) {
                        var version = MigrationVersion.fromVersion(matcher.group(1));
                        if (highest == null || version.compareTo(highest) > 0) {
                            highest = version;
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't list the customer migrations on the classpath", e);
        }
        if (highest == null) {
            throw new IllegalStateException("No customer migrations found on the classpath (db/migration, db/baseline)");
        }
        return highest;
    }
}
