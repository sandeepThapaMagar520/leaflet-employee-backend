package com.ems.backend.common;

import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class SecurityUtils {
    private static final String CURRENT_USER_ATTRIBUTE = SecurityUtils.class.getName() + ".currentUser";
    private final UserRepository userRepository;

    public SecurityUtils(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "Unauthorized");
        }
        return authentication.getName().toLowerCase();
    }

    public User getCurrentUser() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null) {
            Object cached = requestAttributes.getAttribute(CURRENT_USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof User user) {
                return user;
            }
        }
        String email = getCurrentUserEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Current user not found"));
        if (requestAttributes != null) {
            requestAttributes.setAttribute(CURRENT_USER_ATTRIBUTE, user, RequestAttributes.SCOPE_REQUEST);
        }
        return user;
    }
}
