package com.afrotransact.taxreturns.application;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.ClassificationRule;
import com.afrotransact.taxreturns.domain.tax.TaxClassification;
import com.afrotransact.taxreturns.domain.tax.TaxCode;
import com.afrotransact.taxreturns.port.TaxClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxClassificationServiceImpl implements TaxClassificationService {

    private final TaxClassificationRepository repository;
    private final TaxClassifierProvider classifier;
    private final Access access;

    TaxClassificationServiceImpl(TaxClassificationRepository repository, TaxClassifierProvider classifier,
                                 Access access) {
        this.repository = repository;
        this.classifier = classifier;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxCode> codes() {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return repository.codes();
    }

    @Override
    @Transactional
    public TaxCode saveCode(TaxCode code) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (code.label() == null || code.label().isBlank()) {
            throw invalid("label", "A label is required");
        }
        if (!code.code().matches("[A-Z0-9_]{2,40}")) {
            throw invalid("code", "Capital letters, digits and underscores, 2 to 40 characters");
        }
        TaxCode saved = repository.upsertCode(code);
        classifier.invalidate();
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClassificationRule> rules() {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return repository.rules();
    }

    @Override
    @Transactional
    public ClassificationRule saveRule(ClassificationRule rule) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (repository.code(rule.taxCode()).isEmpty()) {
            throw invalid("taxCode", "Unknown tax code " + rule.taxCode());
        }
        ClassificationRule saved = repository.upsertRule(rule);
        classifier.invalidate();
        return saved;
    }

    @Override
    @Transactional
    public void deleteRule(long id) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        repository.deleteRule(id);
        classifier.invalidate();
    }

    @Override
    @Transactional(readOnly = true)
    public TaxClassification test(String productType, Map<String, String> attributes) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return classifier.current().classify(productType, attributes);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxClassificationRepository.Seen> reviewQueue(boolean includeReviewed) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return repository.reviewQueue(includeReviewed, 200);
    }

    @Override
    @Transactional
    public void markReviewed(String productType, String attrHash, String note) {
        Actor who = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (!repository.markReviewed(productType, attrHash, Access.describe(who), note)) {
            throw invalid("productType", "Nothing to review for " + productType.toLowerCase(Locale.ROOT));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxClassificationRepository.CodeRow> liabilityByCode(YearMonth month) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return repository.liabilityByCode(month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private static ReturnRuleViolation invalid(String field, String message) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(field, message);
        return new ReturnRuleViolation("tax-returns.invalid-classification", "The classification is invalid", errors);
    }
}
