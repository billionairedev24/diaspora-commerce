package com.afrotransact.ai.application;

import java.util.List;
import java.util.Set;

import com.afrotransact.ai.domain.AiFeature;
import com.afrotransact.ai.domain.AiRuleViolation;
import com.afrotransact.ai.domain.TaskType;
import com.afrotransact.ai.domain.guardrail.PiiRedactor;
import com.afrotransact.ai.port.EmbeddingReply;
import org.springframework.stereotype.Service;

@Service
class EmbeddingServiceImpl implements EmbeddingService {

    /** Purposes accepted from callers; anything else is rejected so usage stays attributable. */
    static final Set<String> PURPOSES = Set.of("search-query", "search-document", "listing", "similarity");

    private final ModelAccessService models;
    private final PiiRedactor redactor;
    private final AiSettings settings;

    EmbeddingServiceImpl(ModelAccessService models, PiiRedactor redactor, AiSettings settings) {
        this.models = models;
        this.redactor = redactor;
        this.settings = settings;
    }

    @Override
    public Result embed(Caller caller, String input, String purpose) {
        caller.require(AiPermissions.USE);
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            throw new AiRuleViolation("ai.empty-input", "input is required");
        }
        if (text.length() > settings.maxInputChars() * 4) {
            throw new AiRuleViolation("ai.input-too-long", "input is limited to " + settings.maxInputChars() * 4
                    + " characters");
        }
        String p = purpose == null || purpose.isBlank() ? "search-query" : purpose.trim();
        if (!PURPOSES.contains(p)) {
            throw new AiRuleViolation("ai.unknown-purpose", "purpose must be one of " + PURPOSES);
        }
        EmbeddingReply reply = models.embed(caller, AiFeature.EMBEDDINGS, TaskType.EMBEDDING,
                List.of(redactor.redact(text).text()));
        float[] vector = reply.vector();
        return new Result(vector, reply.model(), vector.length);
    }
}
