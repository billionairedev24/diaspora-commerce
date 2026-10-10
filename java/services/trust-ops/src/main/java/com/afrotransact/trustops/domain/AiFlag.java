package com.afrotransact.trustops.domain;

import java.util.List;

/** One AI triage flag with the listing fields it cites. */
public record AiFlag(String code, String description, List<String> citedFields) {

    public AiFlag {
        citedFields = citedFields == null ? List.of() : List.copyOf(citedFields);
    }
}
