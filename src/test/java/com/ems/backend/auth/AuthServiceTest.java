package com.ems.backend.auth;

import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.security.JwtService;
import com.ems.backend.user.Role;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginRejectsInactiveUser() {
        User user = activeUser();
        user.setActive(false);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));

        assertThrows(ResponseStatusException.class, () -> authService.login(new LoginRequest("admin@example.com", "password")));
        verify(loginRateLimiter).recordFailure("admin@example.com");
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    void loginSucceedsForActiveUser() {
        User user = activeUser();
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(jwtService.generateToken("admin@example.com", "ADMIN")).thenReturn("token-123");

        var response = authService.login(new LoginRequest("admin@example.com", "password"));

        assertEquals("token-123", response.accessToken());
        verify(loginRateLimiter).clear("admin@example.com");
    }

    @Test
    void changePasswordRejectsWrongCurrentPassword() {
        User user = activeUser();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> authService.changePassword(new ChangePasswordRequest("wrong", "new-password")));
        verify(userRepository, never()).save(user);
    }

    @Test
    void changePasswordSavesEncodedPassword() {
        User user = activeUser();
        when(securityUtils.getCurrentUser()).thenReturn(user);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-encoded");

        authService.changePassword(new ChangePasswordRequest("password", "new-password"));

        assertEquals("new-encoded", user.getPassword());
        verify(userRepository).save(user);
    }

    private static User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("admin@example.com");
        user.setFullName("Admin");
        user.setPassword("encoded");
        user.setRole(Role.ADMIN);
        user.setActive(true);
        return user;
    }
}
