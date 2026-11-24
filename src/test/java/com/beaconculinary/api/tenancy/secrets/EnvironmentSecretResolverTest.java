package com.beaconculinary.api.tenancy.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-1: secret lookup from the environment. A name resolves through its SECRET_ variable; a missing secret's error
 * names the secret and the variable, never a value; bad names are refused; nothing secret reaches the output.
 */
@ExtendWith(OutputCaptureExtension.class)
class EnvironmentSecretResolverTest {
    /** Not a real secret: it only has to be recognisable. */
    private static final String VALUE = "Test-Only-Value-91c2";

    @Test
    void aName_resolvesFromItsEnvironmentVariable() {
        var environment = new MockEnvironment().withProperty("SECRET_BEACONCULINARY_DB_PASSWORD", VALUE);
        var resolver = new EnvironmentSecretResolver(environment);
        assertThat(resolver.resolve("beaconculinary-db-password")).isEqualTo(VALUE);
    }

    @Test
    void theNamingRule() {
        assertThat(SecretNames.environmentVariable("beaconculinary-db-password")).isEqualTo("SECRET_BEACONCULINARY_DB_PASSWORD");
        assertThat(SecretNames.environmentVariable("pos2-db-login")).isEqualTo("SECRET_POS2_DB_LOGIN");
        assertThat(SecretNames.environmentVariable("x")).isEqualTo("SECRET_X");
        assertThat(SecretNames.isValid("a".repeat(127))).isTrue();
        for (var bad : List.of("", "Beacon-db", "beacon_db", "-beacon", "beacon-", "beacon--db", "beacon db", "a".repeat(128))) {
            assertThat(SecretNames.isValid(bad)).as("[" + bad + "]").isFalse();
        }
        assertThat(SecretNames.isValid(null)).isFalse();
    }

    @Test
    void aMissingSecret_namesTheSecretAndTheVariable_notAValue(CapturedOutput output) {
        // Another secret has a value; it must not leak into the error about the missing one.
        var environment = new MockEnvironment().withProperty("SECRET_OTHER_DB_PASSWORD", VALUE);
        var resolver = new EnvironmentSecretResolver(environment);

        assertThatThrownBy(() -> resolver.resolve("testcustomer-db-password"))
                .isInstanceOf(MissingSecretException.class)
                .hasMessage("Secret 'testcustomer-db-password' is not set (environment variable SECRET_TESTCUSTOMER_DB_PASSWORD)")
                .satisfies(e -> assertThat(((MissingSecretException) e).getSecretName()).isEqualTo("testcustomer-db-password"));
        // An empty value counts as missing.
        var empty = new EnvironmentSecretResolver(new MockEnvironment().withProperty("SECRET_EMPTY_ONE", ""));
        assertThatThrownBy(() -> empty.resolve("empty-one")).isInstanceOf(MissingSecretException.class);
        assertThat(output.getAll().contains(VALUE)).isFalse();
    }

    @Test
    void aBadName_isRefused_beforeAnythingIsRead() {
        var resolver = new EnvironmentSecretResolver(new MockEnvironment().withProperty("SECRET_BEACON_DB", VALUE));
        assertThatThrownBy(() -> resolver.resolve("Beacon_DB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a valid secret name: 'Beacon_DB'")
                .message().doesNotContain(VALUE);
    }

    @Test
    void resolving_printsNothing(CapturedOutput output) {
        var resolver = new EnvironmentSecretResolver(new MockEnvironment().withProperty("SECRET_QUIET_DB_PASSWORD", VALUE));
        for (var i = 0; i < 3; i++) {
            resolver.resolve("quiet-db-password");
        }
        assertThat(output.getAll().contains(VALUE)).isFalse();
    }
}
