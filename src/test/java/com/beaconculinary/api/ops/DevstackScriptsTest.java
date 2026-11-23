package com.beaconculinary.api.ops;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-L0: the local stack's PowerShell scripts (devstack/scripts) parse, and devstack/scripts/tests/devstack.tests.ps1
 * passes: the settings rules, the customer-name rule, and reset.ps1 refusing a database that isn't the stack's or
 * running without its typed confirmation. No Docker needed. Skipped where Windows PowerShell isn't available.
 */
class DevstackScriptsTest {
    private static final Path SCRIPTS = Path.of("devstack", "scripts");

    @Test
    void everyScriptParses() throws Exception {
        assumeWindowsPowerShell();
        try (var files = Files.list(SCRIPTS)) {
            assertThat(files.filter(file -> file.toString().endsWith(".ps1")).count()).as("devstack scripts").isGreaterThanOrEqualTo(7);
        }
        var command = "$bad = 0; Get-ChildItem -Recurse -Filter *.ps1 '" + SCRIPTS.toAbsolutePath() + "' | ForEach-Object { "
                + "$t = $null; $e = $null; [System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$t, [ref]$e) > $null; "
                + "if ($e) { $bad++; $e | ForEach-Object { 'PARSE ' + $_.Extent.File + ':' + $_.Extent.StartLineNumber + ' ' + $_.Message } } }; "
                + "if ($bad) { exit 1 } else { 'parsed' }";
        var result = run(List.of("-Command", command));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("parsed").doesNotContain("PARSE ");
    }

    @Test
    void theScriptTestsPass() throws Exception {
        assumeWindowsPowerShell();
        var tests = SCRIPTS.resolve("tests").resolve("devstack.tests.ps1");
        assertThat(tests).isRegularFile();
        var result = run(List.of("-File", tests.toAbsolutePath().toString()));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("All cases passed").doesNotContain("FAIL");
    }

    private record Result(int exitCode, String output) {
    }

    private static void assumeWindowsPowerShell() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                "Windows PowerShell only");
    }

    private static Result run(List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass"));
        command.addAll(arguments);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException notInstalled) {
            Assumptions.abort("powershell.exe isn't available");
            throw notInstalled;
        }
        var finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished).as("finished within 2 minutes").isTrue();
        return new Result(process.exitValue(), output);
    }
}
