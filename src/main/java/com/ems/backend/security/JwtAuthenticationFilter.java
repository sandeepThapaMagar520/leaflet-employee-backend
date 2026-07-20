package com.ems.backend.security;

import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ApiErrorWriter errorWriter;
    private final SecurityAuditService auditService;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            ApiErrorWriter errorWriter,
            SecurityAuditService auditService
    ) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.errorWriter = errorWriter;
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtService.parseToken(token);
            String email = claims.getSubject();
            int tokenSecurityVersion = jwtService.requireSecurityVersion(claims);
            if (email == null || email.isBlank()) {
                reject(request, response, null, null, "TOKEN_INVALID");
                return;
            }

            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                User user = userRepository.findByEmail(email.toLowerCase()).orElse(null);
                if (user == null
                        || Boolean.FALSE.equals(user.getActive())
                        || user.getSecurityVersion() == null
                        || user.getSecurityVersion() != tokenSecurityVersion) {
                    reject(request, response, user, email, "TOKEN_REVOKED");
                    return;
                }
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                user.getEmail(),
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
                        );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception exception) {
            reject(request, response, null, null, "TOKEN_INVALID");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            User user,
            String email,
            String reasonCode
    ) throws IOException {
        SecurityContextHolder.clearContext();
        auditService.recordBestEffort(
                user == null ? null : user.getId(),
                "TOKEN_REJECTED",
                reasonCode,
                email,
                RequestMetadata.from(request)
        );
        errorWriter.write(
                request,
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "AUTHENTICATION_INVALID",
                "Authentication is missing, expired, invalid, or revoked."
        );
    }
}
