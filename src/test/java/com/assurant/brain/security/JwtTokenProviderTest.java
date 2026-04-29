package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET_32 = "super-secret-key-that-is-32-char";

    private JwtTokenProvider providerWith(String secret, int ttl) {
        var security = new BrainProperties.Security(secret, ttl, "http://localhost:3000", 10, 60, 60000, false);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, security, null, null, null, null, null, null, null, null, null);
        return new JwtTokenProvider(props);
    }

    @Test
    @DisplayName("creates and validates a token round-trip")
    void roundTrip() {
        JwtTokenProvider provider = providerWith(SECRET_32, 60);
        String token = provider.createToken("user-42");
        Optional<String> result = provider.validateAndExtractUserId(token);
        assertThat(result).contains("user-42");
    }

    @Test
    @DisplayName("returns empty for tampered token")
    void tamperedToken() {
        JwtTokenProvider provider = providerWith(SECRET_32, 60);
        String token = provider.createToken("user-1");
        Optional<String> result = provider.validateAndExtractUserId(token + "tampered");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty for garbage token")
    void garbageToken() {
        JwtTokenProvider provider = providerWith(SECRET_32, 60);
        Optional<String> result = provider.validateAndExtractUserId("not.a.jwt");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("tokens from different secrets are incompatible")
    void differentSecrets() {
        JwtTokenProvider provider1 = providerWith(SECRET_32, 60);
        JwtTokenProvider provider2 = providerWith("another-secret-key-32-characters!", 60);
        String token = provider1.createToken("user-1");
        assertThat(provider2.validateAndExtractUserId(token)).isEmpty();
    }

    @Test
    @DisplayName("falls back to generated key when secret too short")
    void shortSecretFallback() {
        JwtTokenProvider provider = providerWith("short", 60);
        String token = provider.createToken("user-1");
        assertThat(provider.validateAndExtractUserId(token)).contains("user-1");
    }

    @Test
    @DisplayName("falls back to generated key when security is null")
    void nullSecurityFallback() {
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        JwtTokenProvider provider = new JwtTokenProvider(props);
        String token = provider.createToken("user-1");
        assertThat(provider.validateAndExtractUserId(token)).contains("user-1");
    }
}
