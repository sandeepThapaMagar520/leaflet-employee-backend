package com.ems.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ApiRateLimitFilterTest {
    private final ApiErrorWriter errorWriter = new ApiErrorWriter(new com.fasterxml.jackson.databind.ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void limitsAuthenticatedReadsWithoutAffectingTheAllowedRequests() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                new ApiRateLimitProperties(true, 2, 1, 1, 1), errorWriter
        );
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user@example.com", null, java.util.List.of())
        );
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletResponse first = apply(filter, chain, "GET", "/api/v1/tasks");
        MockHttpServletResponse second = apply(filter, chain, "GET", "/api/v1/projects");
        MockHttpServletResponse rejected = apply(filter, chain, "GET", "/api/v1/users/me");

        assertEquals(200, first.getStatus());
        assertEquals("1", first.getHeader("X-RateLimit-Remaining"));
        assertEquals(200, second.getStatus());
        assertEquals("0", second.getHeader("X-RateLimit-Remaining"));
        assertEquals(429, rejected.getStatus());
        assertEquals("RATE_LIMITED", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(rejected.getContentAsString()).path("code").asText());
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void usesSeparateBudgetsForReadsWritesAndExpensiveReads() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(
                new ApiRateLimitProperties(true, 1, 1, 1, 1), errorWriter
        );
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user@example.com", null, java.util.List.of())
        );
        FilterChain chain = mock(FilterChain.class);

        assertEquals(200, apply(filter, chain, "GET", "/api/v1/tasks").getStatus());
        assertEquals(200, apply(filter, chain, "POST", "/api/v1/tasks").getStatus());
        assertEquals(200, apply(filter, chain, "GET", "/api/v1/attendance/daily").getStatus());
        assertEquals(429, apply(filter, chain, "GET", "/api/v1/attendance/daily").getStatus());
    }

    private MockHttpServletResponse apply(
            ApiRateLimitFilter filter,
            FilterChain chain,
            String method,
            String path
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
