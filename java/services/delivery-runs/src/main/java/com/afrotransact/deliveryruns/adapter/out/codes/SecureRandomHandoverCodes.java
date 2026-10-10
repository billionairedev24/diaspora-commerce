package com.afrotransact.deliveryruns.adapter.out.codes;

import java.security.SecureRandom;

import com.afrotransact.deliveryruns.port.HandoverCodes;
import org.springframework.stereotype.Component;

/** Six-digit handover codes from a CSPRNG (a code identifies one order on one run, together with the run). */
@Component
class SecureRandomHandoverCodes implements HandoverCodes {

    private final SecureRandom random = new SecureRandom();

    @Override
    public String next() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
