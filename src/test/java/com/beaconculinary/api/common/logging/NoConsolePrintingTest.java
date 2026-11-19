package com.beaconculinary.api.common.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-1: application code logs through SLF4J only. Console printing bypasses the JSON format, the
 * request id and the log levels, so it is refused in src/main/java.
 */
class NoConsolePrintingTest {
    private static final Pattern CONSOLE = Pattern.compile("System\\.(out|err)\\b|\\.printStackTrace\\s*\\(");

    @Test
    void srcMainJava_hasNoSystemOutSystemErrOrPrintStackTrace() throws IOException {
        var root = Path.of("src", "main", "java");
        assertThat(root).isDirectory();
        var found = new ArrayList<String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (CONSOLE.matcher(lines.get(i)).find()) {
                        found.add(root.relativize(file) + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertThat(found).as("Use an SLF4J logger instead").isEmpty();
    }
}
