package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.ReasonCode;

/** Seeded review reason codes. */
public interface ReasonCodeRepository {

    List<ReasonCode> findAll();

    Optional<ReasonCode> find(String code);
}
