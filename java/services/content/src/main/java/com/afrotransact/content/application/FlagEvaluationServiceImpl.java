package com.afrotransact.content.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.afrotransact.content.domain.flag.EvaluationContext;
import com.afrotransact.content.domain.flag.FlagEvaluation;
import com.afrotransact.content.domain.flag.FlagEvaluator;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.springframework.stereotype.Service;

@Service
class FlagEvaluationServiceImpl implements FlagEvaluationService {

    private final FlagCache cache;
    private final FlagEvaluator evaluator;
    private final ContentAccess access;

    FlagEvaluationServiceImpl(FlagCache cache, FlagEvaluator evaluator, ContentAccess access) {
        this.cache = cache;
        this.evaluator = evaluator;
        this.access = access;
    }

    @Override
    public FlagResults evaluateAll(String subject, String market, Set<String> requestedRoles, Collection<String> keys) {
        FlagCache.Snapshot snap = cache.get();
        EvaluationContext ctx = new EvaluationContext(subject, market, trustedRoles(requestedRoles));
        List<FlagEvaluation> out = snap.flags().values().stream()
                .filter(f -> keys == null || keys.isEmpty() || keys.contains(f.key()))
                .map(f -> evaluator.evaluate(f, ctx))
                .toList();
        String etag = Fingerprint.of(snap.etag(), ctx.subject(), ctx.market(), new TreeSet<>(ctx.roles()),
                keys == null ? "" : new TreeSet<>(keys));
        return new FlagResults(out, etag);
    }

    @Override
    public Optional<FlagEvaluation> evaluate(String key, String subject, String market, Set<String> requestedRoles) {
        return Optional.ofNullable(cache.get().flags().get(key))
                .map(f -> evaluator.evaluate(f, new EvaluationContext(subject, market, trustedRoles(requestedRoles))));
    }

    private Set<String> trustedRoles(Set<String> requested) {
        Optional<Actor> actor = access.optional();
        if (actor.isEmpty()) {
            return Set.of();
        }
        if (actor.get().type() == PrincipalType.SERVICE) {
            return requested == null ? Set.of() : requested;
        }
        return actor.get().roles();
    }
}
