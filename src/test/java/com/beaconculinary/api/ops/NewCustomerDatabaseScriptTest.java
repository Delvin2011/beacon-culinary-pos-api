package com.beaconculinary.api.ops;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-7: runs scripts/tests/new-customer-database.tests.ps1, which checks scripts/new-customer-database.ps1 with
 * java and Read-Host stubbed: every exit path returns the right code, and a password the script asked for is cleared
 * on failure as well as on success. Skipped where Windows PowerShell isn't available.
 */
class NewCustomerDatabaseScriptTest {
    private static final Path TESTS = Path.of("scripts", "tests", "new-customer-database.tests.ps1");

    @Test
    void theScriptClearsThePasswordItAskedFor_onEveryExitPath() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "Windows PowerShell only");
        assertThat(TESTS).isRegularFile();
        var process = start();
        Assumptions.assumeTrue(process != null, "powershell.exe isn't available");
        var finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("the script tests finished").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("All cases passed").doesNotContain("FAIL");
    }

    private static Process start() {
        try {
            return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", TESTS.toAbsolutePath().toString()).redirectErrorStream(true).start();
        } catch (IOException notInstalled) {
            return null;
        }
    }
}
