package com.beaconculinary.api.devices;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceTokensTest {

    @Test
    void newToken_isWellFormed_43CharBase64url_andUnique() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            var token = DeviceTokens.newToken();
            assertThat(token).hasSize(43);
            assertThat(DeviceTokens.isWellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void isWellFormed_rejectsWrongShapes() {
        assertThat(DeviceTokens.isWellFormed(null)).isFalse();
        assertThat(DeviceTokens.isWellFormed("")).isFalse();
        assertThat(DeviceTokens.isWellFormed("a".repeat(42))).isFalse();
        assertThat(DeviceTokens.isWellFormed("a".repeat(44))).isFalse();
        assertThat(DeviceTokens.isWellFormed("+".repeat(43))).isFalse();
        assertThat(DeviceTokens.isWellFormed("a".repeat(42) + "=")).isFalse();
    }

    @Test
    void hash_isSha256_andComparedByValue() {
        var token = DeviceTokens.newToken();
        var hash = DeviceTokens.hash(token);
        assertThat(hash).hasSize(32);
        assertThat(DeviceTokens.sameHash(hash, DeviceTokens.hash(token))).isTrue();
        assertThat(DeviceTokens.sameHash(hash, DeviceTokens.hash(DeviceTokens.newToken()))).isFalse();
    }

    @Test
    void serial_isNormalised_whitespaceRemovedAndUpperCased() {
        assertThat(DeviceTokens.normaliseSerial("  ab 12-cd\t34 ")).isEqualTo("AB12-CD34");
        assertThat(DeviceTokens.normaliseSerial("   ")).isNull();
        assertThat(DeviceTokens.normaliseSerial(null)).isNull();
        assertThat(DeviceTokens.normaliseAssetTag("  Till 3 ")).isEqualTo("Till 3");
        assertThat(DeviceTokens.normaliseAssetTag("  ")).isNull();
    }
}
