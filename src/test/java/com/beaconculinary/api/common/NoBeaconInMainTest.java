package com.beaconculinary.api.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-7: no customer's name is built into the app; each customer's name comes from its own settings. So "beacon"
 * (any case) may not appear in src/main, except:
 * <ul>
 *   <li>the Java package {@code com.beaconculinary}, its environment-variable form {@code COM_BEACONCULINARY}
 *       (logging levels) and the main class {@code BeaconCulinaryApiApplication}: renaming them is a separate
 *       change (open item);</li>
 *   <li>the applied migrations (src/main/resources/db/migration), which hold the demo data and are never edited.</li>
 * </ul>
 */
class NoBeaconInMainTest {
    private static final Path ROOT = Path.of("src", "main");
    private static final Path APPLIED_MIGRATIONS = ROOT.resolve(Path.of("resources", "db", "migration"));
    private static final List<String> ALLOWED = List.of("com.beaconculinary", "COM_BEACONCULINARY", "BeaconCulinaryApiApplication");
    private static final Pattern BEACON = Pattern.compile("beacon", Pattern.CASE_INSENSITIVE);

    @Test
    void srcMain_namesNoCustomer() throws IOException {
        assertThat(ROOT).isDirectory();
        var found = new ArrayList<String>();
        try (var files = Files.walk(ROOT)) {
            for (var file : files.filter(Files::isRegularFile).filter(f -> !f.startsWith(APPLIED_MIGRATIONS)).toList()) {
                var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (BEACON.matcher(withoutAllowed(lines.get(i))).find()) {
                        found.add(ROOT.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found).as("A customer's name belongs in its own settings, not in the code").isEmpty();
    }

    @Test
    void theGuardCatchesANameButNotThePackage() {
        assertThat(BEACON.matcher(withoutAllowed("name: beacon-culinary-api")).find()).isTrue();
        assertThat(BEACON.matcher(withoutAllowed("\"Beacon Culinary\"")).find()).isTrue();
        assertThat(BEACON.matcher(withoutAllowed("package com.beaconculinary.api;")).find()).isFalse();
        assertThat(BEACON.matcher(withoutAllowed("LOGGING_LEVEL_COM_BEACONCULINARY_API=DEBUG")).find()).isFalse();
        assertThat(BEACON.matcher(withoutAllowed("BeaconCulinaryApiApplication.class")).find()).isFalse();
    }

    private static String withoutAllowed(String line) {
        var rest = line;
        for (var allowed : ALLOWED) {
            rest = rest.replace(allowed, "");
        }
        return rest;
    }
}
