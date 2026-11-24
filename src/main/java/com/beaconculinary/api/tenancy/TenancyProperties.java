package com.beaconculinary.api.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * T1-1: {@code app.tenancy.*} (docs/2026-11-24-platform-database.md). No toString: the platform password must never
 * reach a log line.
 */
@ConfigurationProperties(prefix = "app.tenancy")
public class TenancyProperties {
    /** Credentials in a JDBC URL (user=, password= and their aliases): the URL must not carry them. */
    private static final Pattern CREDENTIALS_IN_URL = Pattern.compile("(?i)[;?&](user|username|password|pwd|uid)\\s*=");

    private TenancyMode mode = TenancyMode.SINGLE;
    private final Platform platform = new Platform();
    /** How long the customer directory's snapshot is used before it is read again. */
    private Duration directoryCacheTtl = Duration.ofSeconds(30);

    public TenancyMode getMode() {
        return mode;
    }

    public void setMode(TenancyMode mode) {
        this.mode = mode == null ? TenancyMode.SINGLE : mode;
    }

    public Platform getPlatform() {
        return platform;
    }

    public Duration getDirectoryCacheTtl() {
        return directoryCacheTtl;
    }

    public void setDirectoryCacheTtl(Duration directoryCacheTtl) {
        this.directoryCacheTtl = directoryCacheTtl;
    }

    public boolean isMulti() {
        return mode == TenancyMode.MULTI;
    }

    /**
     * What is wrong with the multi-mode settings, by setting name and never by value; empty when they are complete.
     */
    public List<String> problemsForMultiMode() {
        var problems = new ArrayList<String>();
        if (blank(platform.url)) {
            problems.add("app.tenancy.platform.url (APP_TENANCY_PLATFORM_URL) is not set");
        } else if (CREDENTIALS_IN_URL.matcher(platform.url).find()) {
            problems.add("app.tenancy.platform.url must not contain a user or password: set app.tenancy.platform.username and password");
        } else if (!platform.url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlserver://")) {
            problems.add("app.tenancy.platform.url must be a jdbc:sqlserver:// URL");
        }
        if (blank(platform.username)) {
            problems.add("app.tenancy.platform.username (APP_TENANCY_PLATFORM_USERNAME) is not set");
        }
        if (blank(platform.password)) {
            problems.add("app.tenancy.platform.password (APP_TENANCY_PLATFORM_PASSWORD) is not set");
        }
        if (platform.poolSize < 1 || platform.poolSize > 20) {
            problems.add("app.tenancy.platform.pool-size must be 1 to 20");
        }
        if (directoryCacheTtl == null || directoryCacheTtl.isNegative()) {
            problems.add("app.tenancy.directory-cache-ttl must be 0 or positive");
        }
        return problems;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** The platform database's own connection: separate from the customer data source. */
    public static class Platform {
        private String url;
        private String username;
        private String password;
        private int poolSize = 3;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        @Override
        public String toString() {
            return "Platform[url=" + url + ", username=" + username + ", poolSize=" + poolSize + "]";
        }
    }
}
