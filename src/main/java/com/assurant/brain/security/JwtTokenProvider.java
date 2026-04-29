package com.assurant.brain.security;

import com.assurant.brain.config.properties.BrainProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

@Log4j2
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final int ttlMinutes;

    public JwtTokenProvider(BrainProperties brainProperties) {
        String secret = brainProperties.security() != null ? brainProperties.security().jwtSecret() : null;
        if (secret == null || secret.length() < 32) {
            log.warn("BRAIN_JWT_SECRET is not configured or too short — using a generated key (NOT safe for production)");
            this.signingKey = Jwts.SIG.HS256.key().build();
        } else {
            this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.ttlMinutes = brainProperties.security() != null ? brainProperties.security().jwtTtlMinutes() : 60;
    }

    public String createToken(String userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<String> validateAndExtractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.ofNullable(claims.getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
