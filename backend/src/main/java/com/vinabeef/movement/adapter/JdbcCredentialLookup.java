package com.vinabeef.movement.adapter;

import com.vinabeef.movement.port.CredentialLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link CredentialLookup} over the Safetrust read history and the tag register. */
@Component
@RequiredArgsConstructor
public class JdbcCredentialLookup implements CredentialLookup {

    private final JdbcTemplate jdbc;

    @Override
    public List<String> cardsByHistory(String credential) {
        LocalReadSql.Query history = LocalReadSql.cardByHistory(credential);
        return jdbc.queryForList(history.sql(), String.class, history.params().toArray());
    }

    @Override
    public List<String> cardsByRegister(String credential) {
        LocalReadSql.Query register = LocalReadSql.cardByRegister(credential);
        return jdbc.queryForList(register.sql(), String.class, register.params().toArray());
    }
}
