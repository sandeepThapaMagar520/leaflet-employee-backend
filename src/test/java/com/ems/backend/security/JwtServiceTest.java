package com.ems.backend.security;

import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    private static final String SECRET = "D8v!L2q#W9s@M4x%T7k&N1c*R6p-Z3h+F5j";

    @Test
    void validTokenContainsRequiredClaimsAndVersion() {
        JwtService service = service(SECRET, "issuer-a", "audience-a", 900_000);
        User user = user(4);

        String token = service.generateToken(user);
        Claims claims = service.parseToken(token);

        assertEquals(user.getEmail(), claims.getSubject());
        assertEquals("issuer-a", claims.getIssuer());
        assertEquals(4, service.requireSecurityVersion(claims));
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertNotNull(claims.getId());
    }

    @Test
    void expiredTokenFails() {
        JwtService service = service(SECRET, "issuer-a", "audience-a", -1);
        assertThrows(JwtException.class, () -> service.parseToken(service.generateToken(user(1))));
    }

    @Test
    void invalidSignatureIssuerAndAudienceFail() {
        String token = service(SECRET, "issuer-a", "audience-a", 900_000).generateToken(user(1));

        assertThrows(
                JwtException.class,
                () -> service("Q9w!E2r#T5y@U8i%O1p&A4s*D7f-G0h+J3k", "issuer-a", "audience-a", 900_000)
                        .parseToken(token)
        );
        assertThrows(
                JwtException.class,
                () -> service(SECRET, "issuer-b", "audience-a", 900_000).parseToken(token)
        );
        assertThrows(
                JwtException.class,
                () -> service(SECRET, "issuer-a", "audience-b", 900_000).parseToken(token)
        );
    }

    @Test
    void missingAndMalformedSecurityVersionFailClosed() {
        JwtService service = service(SECRET, "issuer-a", "audience-a", 900_000);
        Claims missing = service.parseToken(customToken(null));
        Claims malformed = service.parseToken(customToken("one"));

        assertThrows(JwtException.class, () -> service.requireSecurityVersion(missing));
        assertThrows(JwtException.class, () -> service.requireSecurityVersion(malformed));
    }

    private String customToken(Object securityVersion) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject("employee@example.net")
                .issuer("issuer-a")
                .audience().add("audience-a").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)));
        if (securityVersion != null) {
            builder.claim("sv", securityVersion);
        }
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private JwtService service(
            String secret,
            String issuer,
            String audience,
            long expirationMs
    ) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setIssuer(issuer);
        properties.setAudience(audience);
        properties.setKeyId("test-key");
        properties.setExpirationMs(expirationMs);
        return new JwtService(properties);
    }

    private User user(int version) {
        User user = new User();
        user.setEmail("employee@example.net");
        user.setRole(Role.EMPLOYEE);
        user.setSecurityVersion(version);
        return user;
    }
}
