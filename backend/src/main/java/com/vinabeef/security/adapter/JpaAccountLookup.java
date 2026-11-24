package com.vinabeef.security.adapter;

import com.vinabeef.security.UserRepository;
import com.vinabeef.security.port.AccountLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** {@link AccountLookup} over the user repository. */
@Component
@RequiredArgsConstructor
public class JpaAccountLookup implements AccountLookup {

    private final UserRepository users;

    @Override
    public Optional<AccountView> byUsername(String username) {
        return users.findByUsername(username)
                .map(u -> new AccountView(u.getUsername(), u.getRole(), u.isEnabled(), u.getAssignedFarmIds()));
    }
}
