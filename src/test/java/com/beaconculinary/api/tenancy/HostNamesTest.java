package com.beaconculinary.api.tenancy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** T1-1: a request's host made comparable with the stored ones. */
class HostNamesTest {

    @Test
    void caseThePortAndATrailingDot_areIgnored() {
        for (var raw : new String[]{"beaconculinary.pos.localhost", "BeaconCulinary.POS.localhost",
                "beaconculinary.pos.localhost:443", "beaconculinary.pos.localhost.", "  BEACONCULINARY.pos.localhost.:8443  "}) {
            assertThat(HostNames.normalise(raw)).as(raw).contains("beaconculinary.pos.localhost");
        }
        assertThat(HostNames.normalise("localhost:8080")).contains("localhost");
        assertThat(HostNames.normalise("a-1.b-2.example")).contains("a-1.b-2.example");
    }

    @Test
    void anythingThatIsNotAHostName_givesNoHost() {
        var bad = new String[]{null, "", "   ", "[::1]", "[::1]:443", "::1", "host:port", "host:123456", "host:443:1",
                "two words.example", "under_score.example", "-lead.example", "trail-.example", "a..b", ".lead",
                "caf" + (char) 0xE9 + ".example", "x".repeat(254), "host/path", "user@host"};
        for (var raw : bad) {
            assertThat(HostNames.normalise(raw)).as(String.valueOf(raw)).isEmpty();
        }
        assertThat(Arrays.stream(bad).filter(b -> b != null && b.length() > 250)).hasSize(1);
    }
}
