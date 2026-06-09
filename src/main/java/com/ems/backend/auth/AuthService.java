package com.ems.backend.auth;

import com.ems.backend.auth.dto.ChangePasswordRequest;
import com.ems.backend.auth.dto.AuthResponse;
import com.ems.backend.auth.dto.LoginRequest;
import com.ems.backend.auth.dto.RegisterRequest;
import com.ems.backend.security.JwtService;
import com.ems.backend.common.SecurityUtils;
import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginRateLimiter loginRateLimiter;
    private final SecurityUtils securityUtils;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginRateLimiter loginRateLimiter,
            SecurityUtils securityUtils
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
        this.securityUtils = securityUtils;
    }

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }

        User user = new User();
        user.setFullName(request.fullName());
        user.setEmail(request.email().toLowerCase());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(request.role());

        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved.getEmail(), saved.getRole().name());

        return new AuthResponse(token, "Bearer", saved.getId(), saved.getFullName(), saved.getEmail(), saved.getRole());
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase();
        loginRateLimiter.checkAllowed(email);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    loginRateLimiter.recordFailure(email);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
                });

        if (Boolean.FALSE.equals(user.getActive())) {
            loginRateLimiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been deactivated. Contact your administrator.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }

        loginRateLimiter.clear(email);
        String token = jwtService.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, "Bearer", user.getId(), user.getFullName(), user.getEmail(), user.getRole());
    }

    public void changePassword(ChangePasswordRequest request) {
        User currentUser = securityUtils.getCurrentUser();
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        currentUser.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(currentUser);
    }
}
