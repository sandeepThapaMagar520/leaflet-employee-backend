package com.ems.backend.security;

import com.ems.backend.user.Role;
import com.ems.backend.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthenticationStateCache {
    private final UserRepository userRepository;
    private final AuthenticationCacheProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @Autowired
    public AuthenticationStateCache(
            UserRepository userRepository,
            AuthenticationCacheProperties properties
    ) {
        this(userRepository, properties, Clock.systemUTC());
    }

    AuthenticationStateCache(
            UserRepository userRepository,
            AuthenticationCacheProperties properties,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<AuthenticationState> findByEmail(String email) {
        String key = normalize(email);
        if (!properties.enabled()) {
            return load(key);
        }

        Instant now = clock.instant();
        CacheEntry cached = entries.get(key);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return Optional.of(cached.state());
        }
        if (cached != null) {
            entries.remove(key, cached);
        }
        synchronized (entries) {
            now = clock.instant();
            cached = entries.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) {
                return Optional.of(cached.state());
            }
            Optional<AuthenticationState> loaded = load(key);
            Instant loadedAt = now;
            loaded.ifPresent(state -> {
                maintainCapacity(loadedAt);
                entries.put(key, new CacheEntry(
                        state,
                        loadedAt.plus(Duration.ofSeconds(Math.max(1, properties.ttlSeconds())))
                ));
            });
            return loaded;
        }
    }

    public void evictUserAfterCommit(Long userId) {
        if (userId == null) return;
        runAfterCommit(() -> entries.entrySet().removeIf(entry -> entry.getValue().state().id().equals(userId)));
    }

    private Optional<AuthenticationState> load(String email) {
        return userRepository.findAuthenticationStateByEmail(email)
                .map(row -> new AuthenticationState(
                        row.getId(), row.getEmail(), row.getRole(), row.getActive(), row.getSecurityVersion()
                ));
    }

    private void maintainCapacity(Instant now) {
        int maximumEntries = Math.max(1, properties.maximumEntries());
        if (entries.size() < maximumEntries) return;
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (entries.size() >= maximumEntries) {
            entries.clear();
        }
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthenticationState(
            Long id,
            String email,
            Role role,
            Boolean active,
            Integer securityVersion
    ) {
    }

    private record CacheEntry(AuthenticationState state, Instant expiresAt) {
    }
}
