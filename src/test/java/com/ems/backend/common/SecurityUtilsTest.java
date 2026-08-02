package com.ems.backend.common;

import com.ems.backend.user.User;
import com.ems.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityUtilsTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void reusesCurrentUserWithinOneHttpRequest() {
        UserRepository repository = mock(UserRepository.class);
        User user = new User();
        user.setEmail("employee@example.com");
        when(repository.findByEmail("employee@example.com")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("Employee@Example.com", null)
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );
        SecurityUtils securityUtils = new SecurityUtils(repository);

        assertSame(user, securityUtils.getCurrentUser());
        assertSame(user, securityUtils.getCurrentUser());

        verify(repository, times(1)).findByEmail("employee@example.com");
    }
}
