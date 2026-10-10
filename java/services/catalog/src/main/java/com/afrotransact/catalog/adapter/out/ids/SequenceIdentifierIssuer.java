package com.afrotransact.catalog.adapter.out.ids;

import java.math.BigInteger;
import java.util.Locale;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.port.IdentifierIssuer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Issues identifiers from Postgres sequences. ATINs are a bijective scramble of the sequence value
 * (multiplication by a constant coprime to 36^8), so they are unique but not guessable in order.
 */
@Component
class SequenceIdentifierIssuer implements IdentifierIssuer {

    private static final BigInteger SPACE = BigInteger.valueOf(36).pow(8);
    private static final BigInteger MULTIPLIER = BigInteger.valueOf(1_000_000_007L);
    private static final BigInteger OFFSET = BigInteger.valueOf(36L * 36 * 36 * 36 * 36 * 36 * 36);

    private final JdbcClient jdbc;

    SequenceIdentifierIssuer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Atin nextAtin() {
        long seq = jdbc.sql("select nextval('atin_seq')").query(Long.class).single();
        BigInteger scrambled = BigInteger.valueOf(seq).multiply(MULTIPLIER).add(OFFSET).mod(SPACE);
        String body = scrambled.toString(36).toUpperCase(Locale.ROOT);
        return new Atin("AT" + "0".repeat(8 - body.length()) + body);
    }

    @Override
    public String nextCategoryRequestReference() {
        long seq = jdbc.sql("select nextval('category_request_seq')").query(Long.class).single();
        return "REQ-%06d".formatted(seq);
    }
}
