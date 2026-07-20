package com.ems.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.ems.backend.user.User;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtProperties jwtProperties;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getExpirationMs());

        return Jwts.builder()
                .header()
                    .keyId(jwtProperties.getKeyId())
                    .and()
                .subject(user.getEmail())
                .issuer(jwtProperties.getIssuer())
                .audience()
                    .add(jwtProperties.getAudience())
                    .and()
                .id(UUID.randomUUID().toString())
                .claim("sv", user.getSecurityVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .requireIssuer(jwtProperties.getIssuer())
                .requireAudience(jwtProperties.getAudience())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public int requireSecurityVersion(Claims claims) {
        Object claim = claims.get("sv");
        if (!(claim instanceof Number number)) {
            throw new JwtException("Missing or malformed security version");
        }
        int value = number.intValue();
        if (value <= 0 || number.longValue() != value) {
            throw new JwtException("Malformed security version");
        }
        return value;
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
