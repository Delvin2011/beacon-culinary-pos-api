package com.beaconculinary.api.support.baseline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0-7: generates the baseline migration. Not part of the suite; run it on purpose:
 *
 * <pre>./mvnw test -Dtest=GenerateBaselineRun -Dbaseline.generate=true</pre>
 *
 * It creates a throwaway database, migrates it through every migration in db/migration, reads its schema,
 * and writes target/baseline/B{N}__baseline.sql with the hand-written required data
 * (src/test/resources/baseline/required-data.sql). A person reviews the file before copying it to
 * src/main/resources/db/baseline (docs/2026-11-21-new-customer-database.md, "Re-baselining").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfSystemProperty(named = "baseline.generate", matches = "true")
class GenerateBaselineRun {
    @Autowired
    private Environment environment;

    @Test
    void generate() throws Exception {
        var url = environment.getRequiredProperty("spring.datasource.url");
        var username = environment.getRequiredProperty("spring.datasource.username");
        var password = environment.getRequiredProperty("spring.datasource.password");
        var requiredData = Files.readString(Path.of("src", "test", "resources", "baseline", "required-data.sql"));
        try (var db = ThrowawayDatabase.create(url, username, password, "Generate")) {
            var result = db.flyway("classpath:db/migration").migrate();
            var version = Integer.parseInt(result.targetSchemaVersion);
            String script;
            try (var connection = db.connect()) {
                script = BaselineScriptGenerator.generate(SchemaCatalog.read(connection), version, requiredData);
            }
            var out = Path.of("target", "baseline", "B" + version + "__baseline.sql");
            Files.createDirectories(out.getParent());
            Files.writeString(out, script.replace("\n", "\r\n"), StandardCharsets.UTF_8);
            assertThat(script).contains("CREATE TABLE [users]").doesNotContain("canteen.local");
            System.out.println("Baseline written: " + out.toAbsolutePath());
        }
    }
}
