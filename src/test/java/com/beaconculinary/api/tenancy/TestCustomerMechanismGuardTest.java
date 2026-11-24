package com.beaconculinary.api.tenancy;

import com.beaconculinary.api.support.tenancy.TestCustomerFilter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-2: the test-only customer mechanism (a header that picks the customer) can't reach production. Its classes are
 * compiled from src/test only, so they are not in target/classes (what the jar is built from), and nothing in
 * src/main names the header or the classes. That a multi-mode application without the test configuration ignores the
 * header is shown by MultiModeStartupIntegrationTests.
 */
class TestCustomerMechanismGuardTest {

    @Test
    void theTestOnlyClasses_areNotInTheApplicationClasses() {
        var mainClasses = Path.of("target", "classes");
        assertThat(mainClasses).isDirectory();
        for (var name : new String[]{"TestCustomerFilter", "TestCustomerConfig"}) {
            assertThat(mainClasses.resolve("com/beaconculinary/api/support/tenancy/" + name + ".class")).doesNotExist();
        }
        assertThat(TestCustomerFilter.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .as("compiled into the test classes").contains("test-classes");
    }

    @Test
    void nothingInTheMainSources_namesTheHeaderOrTheTestClasses() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src", "main"))) {
            var offenders = files.filter(Files::isRegularFile).filter(file -> {
                try {
                    var text = Files.readString(file);
                    return text.contains(TestCustomerFilter.HEADER) || text.contains("TestCustomerFilter")
                            || text.contains("TestCustomerConfig");
                } catch (IOException e) {
                    return false; // not text
                }
            }).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
