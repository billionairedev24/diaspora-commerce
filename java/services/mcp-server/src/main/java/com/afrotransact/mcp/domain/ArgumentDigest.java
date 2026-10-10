package com.afrotransact.mcp.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of a tool call's canonical (key-sorted) JSON arguments: audits prove what was asked without storing it. */
public final class ArgumentDigest {

    private ArgumentDigest() {
    }

    public static String sha256(String canonicalArguments) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((canonicalArguments == null ? "" : canonicalArguments)
                    .getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
