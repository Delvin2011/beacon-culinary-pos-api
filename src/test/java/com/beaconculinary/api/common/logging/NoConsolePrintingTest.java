package com.beaconculinary.api.common.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-1: application code logs through SLF4J only. Console printing bypasses the JSON format, the
 * request id and the log levels, so it is refused in src/main/java.
 *
 * <p>T0-6a: one exception, the operator's first-admin command. It runs from a terminal, never inside
 * the web app, and prints the one-time invite link there so it never reaches the logs
 * (docs/2026-11-21-first-admin-and-invites.md). Nothing else in src/main/java may refer to it.
 */
class NoConsolePrintingTest {
    private static final Pattern CONSOLE = Pattern.compile("System\\.(out|err)\\b|\\.printStackTrace\\s*\\(");
    private static final Path ROOT = Path.of("src", "main", "java");
    private static final Path FIRST_ADMIN_COMMAND =
            Path.of("com", "beaconculinary", "api", "ops", "FirstAdminCommand.java");
    /** Files allowed to print to the console. */
    private static final Set<Path> ALLOWED = Set.of(FIRST_ADMIN_COMMAND);

    @Test
    void srcMainJava_hasNoSystemOutSystemErrOrPrintStackTrace() throws IOException {
        assertThat(ROOT).isDirectory();
        var found = new ArrayList<String>();
        try (var files = Files.walk(ROOT)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (ALLOWED.contains(ROOT.relativize(file))) {
                    continue;
                }
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (CONSOLE.matcher(lines.get(i)).find()) {
                        found.add(ROOT.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found).as("Use an SLF4J logger instead").isEmpty();
    }

    @Test
    void theFirstAdminCommandExists_andNothingInTheAppCallsIt() throws IOException {
        assertThat(ROOT.resolve(FIRST_ADMIN_COMMAND)).isRegularFile();
        var callers = new ArrayList<String>();
        try (var files = Files.walk(ROOT)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!ROOT.relativize(file).equals(FIRST_ADMIN_COMMAND)
                        && Files.readString(file).contains("FirstAdminCommand")) {
                    callers.add(ROOT.relativize(file).toString());
                }
            }
        }
        assertThat(callers).as("The first-admin command runs from a terminal only").isEmpty();
    }
}
