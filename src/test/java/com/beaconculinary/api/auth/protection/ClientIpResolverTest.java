package com.beaconculinary.api.auth.protection;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    @Nested
    class WithoutTrustedProxies {
        private final ClientIpResolver resolver = new ClientIpResolver(List.of());

        @Test
        void usesRemoteAddr() {
            assertThat(resolver.resolve("203.0.113.7", null)).isEqualTo("203.0.113.7");
        }

        @Test
        void ignoresForwardedForEntirely() {
            assertThat(resolver.resolve("203.0.113.7", "198.51.100.1")).isEqualTo("203.0.113.7");
        }

        @Test
        void missingOrMalformedRemoteAddr_isNull() {
            assertThat(resolver.resolve(null, "198.51.100.1")).isNull();
            assertThat(resolver.resolve("not-an-ip", null)).isNull();
            assertThat(resolver.resolve("999.1.1.1", null)).isNull();
        }
    }

    @Nested
    class WithTrustedProxies {
        private final ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "2001:db8::1"));

        @Test
        void trustedPeer_takesRightMostUntrustedEntry() {
            assertThat(resolver.resolve("10.1.2.3", "198.51.100.9, 203.0.113.7")).isEqualTo("203.0.113.7");
        }

        @Test
        void trustedPeer_skipsTrustedHopsFromTheRight() {
            assertThat(resolver.resolve("10.1.2.3", "203.0.113.7, 10.9.9.9, 10.4.4.4")).isEqualTo("203.0.113.7");
        }

        @Test
        void spoofedHeaderFromUntrustedPeer_isIgnored() {
            assertThat(resolver.resolve("198.51.100.66", "1.2.3.4")).isEqualTo("198.51.100.66");
        }

        @Test
        void clientCannotInjectALeftmostAddress() {
            // The client sent "X-Forwarded-For: 6.6.6.6"; the proxy appended the real address.
            assertThat(resolver.resolve("10.1.2.3", "6.6.6.6, 203.0.113.7")).isEqualTo("203.0.113.7");
        }

        @Test
        void malformedEntry_fallsBackToPeer_ratherThanTrustingFurtherLeft() {
            assertThat(resolver.resolve("10.1.2.3", "6.6.6.6, evil.example.com")).isEqualTo("10.1.2.3");
            assertThat(resolver.resolve("10.1.2.3", "6.6.6.6, ")).isEqualTo("10.1.2.3");
        }

        @Test
        void onlyTrustedHops_fallsBackToPeer() {
            assertThat(resolver.resolve("10.1.2.3", "10.5.5.5")).isEqualTo("10.1.2.3");
        }

        @Test
        void portsAndIpv6AreHandled() {
            assertThat(resolver.resolve("10.1.2.3", "203.0.113.7:51234")).isEqualTo("203.0.113.7");
            assertThat(resolver.resolve("10.1.2.3", "[2001:DB8::42]:443")).isEqualTo("2001:db8::42");
            assertThat(resolver.resolve("2001:db8::1", "2001:db8::77")).isEqualTo("2001:db8::77");
        }

        @Test
        void blankHeader_usesPeer() {
            assertThat(resolver.resolve("10.1.2.3", "  ")).isEqualTo("10.1.2.3");
        }
    }

    @Test
    void proxyEntryValidation() {
        assertThat(ClientIpResolver.isValidProxyEntry("10.0.0.0/8")).isTrue();
        assertThat(ClientIpResolver.isValidProxyEntry("203.0.113.7")).isTrue();
        assertThat(ClientIpResolver.isValidProxyEntry("2001:db8::/32")).isTrue();
        assertThat(ClientIpResolver.isValidProxyEntry("10.0.0.0/33")).isFalse();
        assertThat(ClientIpResolver.isValidProxyEntry("proxy.internal")).isFalse();
        assertThat(ClientIpResolver.isValidProxyEntry("10.0.0.0/x")).isFalse();
        assertThat(ClientIpResolver.isValidProxyEntry(null)).isFalse();
    }
}
