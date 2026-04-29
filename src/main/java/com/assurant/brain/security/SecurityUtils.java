package com.assurant.brain.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {

    private static final String DEFAULT_USER = "anonymous";

    private SecurityUtils() {}

    public static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return DEFAULT_USER;
        return auth.getPrincipal().toString();
    }
}
