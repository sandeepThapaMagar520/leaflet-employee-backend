package com.ems.backend.security;

import com.ems.backend.user.Role;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationStateCacheTest {
    @Mock private UserRepository userRepository;

    @Test
    void reusesMinimalAuthenticationStateUntilItExpires() {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        UserRepository.AuthenticationStateRow row = row(7L, "employee@example.com", 4);
        when(userRepository.findAuthenticationStateByEmail("employee@example.com"))
                .thenReturn(Optional.of(row));
        AuthenticationStateCache cache = cacheAt(now);

        assertEquals(7L, cache.findByEmail(" Employee@Example.com ").orElseThrow().id());
        assertEquals(7L, cache.findByEmail("employee@example.com").orElseThrow().id());

        verify(userRepository, times(1)).findAuthenticationStateByEmail("employee@example.com");
    }

    @Test
    void doesNotCacheMissingUsers() {
        when(userRepository.findAuthenticationStateByEmail("missing@example.com"))
                .thenReturn(Optional.empty());
        AuthenticationStateCache cache = cacheAt(Instant.parse("2026-08-02T00:00:00Z"));

        cache.findByEmail("missing@example.com");
        cache.findByEmail("missing@example.com");

        verify(userRepository, times(2)).findAuthenticationStateByEmail("missing@example.com");
    }

    @Test
    void defersEvictionUntilTransactionCommit() {
        UserRepository.AuthenticationStateRow row = row(9L, "manager@example.com", 2);
        when(userRepository.findAuthenticationStateByEmail("manager@example.com"))
                .thenReturn(Optional.of(row));
        AuthenticationStateCache cache = cacheAt(Instant.parse("2026-08-02T00:00:00Z"));
        cache.findByEmail("manager@example.com");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.evictUserAfterCommit(9L);
            cache.findByEmail("manager@example.com");
            verify(userRepository, times(1)).findAuthenticationStateByEmail("manager@example.com");

            TransactionSynchronizationUtils.triggerAfterCommit();
            cache.findByEmail("manager@example.com");
            verify(userRepository, times(2)).findAuthenticationStateByEmail("manager@example.com");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    private AuthenticationStateCache cacheAt(Instant instant) {
        return new AuthenticationStateCache(
                userRepository,
                new AuthenticationCacheProperties(true, 15, 100),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private UserRepository.AuthenticationStateRow row(Long id, String email, int securityVersion) {
        UserRepository.AuthenticationStateRow row = mock(UserRepository.AuthenticationStateRow.class);
        when(row.getId()).thenReturn(id);
        when(row.getEmail()).thenReturn(email);
        when(row.getRole()).thenReturn(Role.EMPLOYEE);
        when(row.getActive()).thenReturn(true);
        when(row.getSecurityVersion()).thenReturn(securityVersion);
        return row;
    }
}
