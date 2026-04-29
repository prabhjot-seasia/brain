package com.assurant.brain.security;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
        }
        String token = jwtTokenProvider.createToken(userId);
        return ResponseEntity.ok(Map.of("token", token, "userId", userId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        String currentUserId = SecurityUtils.currentUserId();
        String token = jwtTokenProvider.createToken(currentUserId);
        return ResponseEntity.ok(Map.of("token", token, "userId", currentUserId));
    }
}
