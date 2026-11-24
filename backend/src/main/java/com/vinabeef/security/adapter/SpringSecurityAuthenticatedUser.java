package com.vinabeef.security.adapter;

import com.vinabeef.security.port.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link AuthenticatedUser} read from Spring Security's context. */
@Component
public class SpringSecurityAuthenticatedUser implements AuthenticatedUser {

    @Override
    public Optional<String> username() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return Optional.empty();
        }
        return Optional.of(auth.getName());
    }
}
