package com.afrotransact.identity.testsupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The CBOR subset (RFC 8949) WebAuthn authenticators emit: unsigned/negative integers, byte strings, text strings and
 * maps, definite lengths, written in the given map order (callers pass CTAP2 canonical order). Written by hand so the
 * virtual authenticator does not share code with the server's WebAuthn4J parser.
 */
public final class Cbor {

    private static final int UNSIGNED = 0;
    private static final int NEGATIVE = 1;
    private static final int BYTES = 2;
    private static final int TEXT = 3;
    private static final int MAP = 5;

    private Cbor() {
    }

    /** Encodes an Integer/Long, byte[], String or Map (keys and values of those types). */
    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        switch (value) {
            case Integer i -> write(out, i.longValue());
            case Long l -> {
                if (l >= 0) {
                    head(out, UNSIGNED, l);
                } else {
                    head(out, NEGATIVE, -1 - l);
                }
            }
            case byte[] b -> {
                head(out, BYTES, b.length);
                out.writeBytes(b);
            }
            case String s -> {
                byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
                head(out, TEXT, utf8.length);
                out.writeBytes(utf8);
            }
            case Map<?, ?> m -> {
                head(out, MAP, m.size());
                m.forEach((k, v) -> {
                    write(out, k);
                    write(out, v);
                });
            }
            default -> throw new IllegalArgumentException("Unsupported CBOR value " + value.getClass());
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long length) {
        int type = major << 5;
        if (length < 24) {
            out.write(type | (int) length);
        } else if (length < 0x100) {
            out.write(type | 24);
            out.write((int) length);
        } else if (length < 0x10000) {
            out.write(type | 25);
            out.write((int) (length >>> 8));
            out.write((int) length);
        } else {
            out.write(type | 26);
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((int) (length >>> shift));
            }
        }
    }
}
