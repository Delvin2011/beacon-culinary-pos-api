package com.beaconculinary.api.common.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-1: application code logs through SLF4J only. Console printing bypasses the JSON format, the
 * request id and the log levels, so it is refused in src/main/java.
 *
 * <p>Exceptions: the operator commands, which run from a terminal and never inside the web app. T0-6a's
 * first-admin command prints the one-time invite link there so it never reaches the logs
 * (docs/2026-11-21-first-admin-and-invites.md); T0-7's new-database command prints its summary
 * (docs/2026-11-22-new-customer-database.md). Nothing else in src/main/java may refer to them.
 */
class NoConsolePrintingTest {
    private static final Pattern CONSOLE = Pattern.compile("System\\.(out|err)\\b|\\.printStackTrace\\s*\\(");
    private static final Path ROOT = Path.of("src", "main", "java");
    private static final Path OPS = Path.of("com", "beaconculinary", "api", "ops");
    /** The operator commands: the only files allowed to print to the console. */
    private static final List<String> COMMANDS = List.of("FirstAdminCommand", "NewDatabaseCommand");
    private static final Set<Path> ALLOWED = COMMANDS.stream().map(c -> OPS.resolve(c + ".java")).collect(Collectors.toSet());

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
    void theOperatorCommandsExist_andNothingInTheAppCallsThem() throws IOException {
        ALLOWED.forEach(command -> assertThat(ROOT.resolve(command)).isRegularFile());
        var callers = new ArrayList<String>();
        try (var files = Files.walk(ROOT)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (ALLOWED.contains(ROOT.relativize(file))) {
                    continue;
                }
                var text = Files.readString(file);
                for (var command : COMMANDS) {
                    if (text.contains(command)) {
                        callers.add(ROOT.relativize(file) + " refers to " + command);
                    }
                }
            }
        }
        assertThat(callers).as("The operator commands run from a terminal only").isEmpty();
    }
}
