package com.beaconculinary.api.auth.invite;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T0-6a: invite tokens, their stored hash, and the link. */
class InviteTokensTest {

    @Test
    void tokensAre256RandomBitsAsBase64Url_andNeverRepeat() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            var token = InviteTokens.newToken();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
            assertThat(InviteTokens.wellFormed(token)).isTrue();
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void theStoredFormIsTheSha256Hex_neverTheToken() {
        var token = InviteTokens.newToken();
        var hash = InviteTokens.hash(token);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(InviteTokens.hash(token)).doesNotContain(token);
        assertThat(InviteTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void malformedTokensAreRecognised() {
        assertThat(InviteTokens.wellFormed(null)).isFalse();
        assertThat(InviteTokens.wellFormed("")).isFalse();
        assertThat(InviteTokens.wellFormed("short")).isFalse();
        assertThat(InviteTokens.wellFormed("a".repeat(44))).isFalse();
        assertThat(InviteTokens.wellFormed("a".repeat(42) + "=")).isFalse();
        assertThat(InviteTokens.wellFormed("a".repeat(42) + "/")).isFalse();
    }

    @Test
    void theLinkPutsTheTokenAfterTheHash_soNoServerReceivesIt() {
        var links = new InviteLinks("https://pos.example.com/");
        assertThat(links.configured()).isTrue();
        assertThat(links.link("TOKEN")).isEqualTo("https://pos.example.com/invite#t=TOKEN");
        assertThat(new InviteLinks("").link("TOKEN")).isNull();
        assertThat(new InviteLinks("  ").configured()).isFalse();
    }

    @Test
    void aBadBaseUrlStopsStartup() {
        assertThatThrownBy(() -> new InviteLinks("pos.example.com")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.public-base-url");
        assertThatThrownBy(() -> new InviteLinks("ftp://pos.example.com")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InviteLinks("https://pos.example.com/?x=1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InviteLinks("https://pos.example.com/#a")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void credentialsAndStatus() {
        assertThat(InviteCredentials.BOTH.includesPassword()).isTrue();
        assertThat(InviteCredentials.BOTH.includesPin()).isTrue();
        assertThat(InviteCredentials.PIN.includesPassword()).isFalse();
        assertThat(InviteCredentials.PASSWORD.includesPin()).isFalse();
        assertThat(InviteCredentials.of(true, false)).isEqualTo(InviteCredentials.PASSWORD);
        assertThatThrownBy(() -> InviteCredentials.of(false, false)).isInstanceOf(IllegalArgumentException.class);
    }
}
