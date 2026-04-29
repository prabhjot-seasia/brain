package com.assurant.brain.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthController")
class AuthControllerTest {

    private JwtTokenProvider jwtTokenProvider;
    private AuthController controller;

    @BeforeEach
    void setup() {
        jwtTokenProvider = mock(JwtTokenProvider.class);
        controller = new AuthController(jwtTokenProvider);
    }

    @Test
    @DisplayName("login returns JWT for valid userId")
    void loginSuccess() {
        when(jwtTokenProvider.createToken("user-1")).thenReturn("jwt-token-123");

        ResponseEntity<Map<String, String>> response =
                controller.login(Map.of("userId", "user-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("token", "jwt-token-123");
        assertThat(response.getBody()).containsEntry("userId", "user-1");
    }

    @Test
    @DisplayName("login returns 400 when userId is missing")
    void loginMissingUserId() {
        ResponseEntity<Map<String, String>> response =
                controller.login(Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsKey("error");
    }

    @Test
    @DisplayName("login returns 400 when userId is blank")
    void loginBlankUserId() {
        ResponseEntity<Map<String, String>> response =
                controller.login(Map.of("userId", "   "));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("refresh returns new token for current user")
    void refreshSuccess() {
        when(jwtTokenProvider.createToken("anonymous")).thenReturn("refreshed-token");

        ResponseEntity<Map<String, String>> response = controller.refresh();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("token", "refreshed-token");
    }
}
