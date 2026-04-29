package com.assurant.brain.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityUtils")
class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("returns userId from SecurityContext")
    void returnsAuthenticatedUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-42", null, List.of()));
        assertThat(SecurityUtils.currentUserId()).isEqualTo("user-42");
    }

    @Test
    @DisplayName("returns anonymous when no authentication")
    void returnsAnonymousWhenNoAuth() {
        assertThat(SecurityUtils.currentUserId()).isEqualTo("anonymous");
    }

    @Test
    @DisplayName("returns anonymous when principal is null")
    void returnsAnonymousWhenNullPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(null, null));
        assertThat(SecurityUtils.currentUserId()).isEqualTo("anonymous");
    }
}
