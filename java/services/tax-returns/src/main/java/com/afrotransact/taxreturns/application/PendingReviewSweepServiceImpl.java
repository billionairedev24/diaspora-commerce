package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.port.TaxReviewSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class PendingReviewSweepServiceImpl implements PendingReviewSweepService {

    private static final Logger LOG = LoggerFactory.getLogger(PendingReviewSweepServiceImpl.class);

    private final VariantClassificationService variants;
    private final TaxReviewSettingsRepository settings;

    PendingReviewSweepServiceImpl(VariantClassificationService variants, TaxReviewSettingsRepository settings) {
        this.variants = variants;
        this.settings = settings;
    }

    @Override
    public Report sweep() {
        if (!settings.current().riskBased()) {
            return new Report(0, 0, 0);
        }
        List<String> waiting = variants.pendingReviewAtins();
        int approved = 0;
        int skipped = 0;
        for (String atin : waiting) {
            try {
                if (variants.autoApproveIfQualified(atin)) {
                    approved++;
                }
            } catch (RuntimeException ex) {
                skipped++;
                LOG.warn("Review sweep skipped {}: {}", atin, ex.getMessage());
            }
        }
        if (approved > 0) {
            LOG.info("Review sweep: {} of {} records waiting in review were approved by the risk-based rule", approved,
                    waiting.size());
        }
        return new Report(waiting.size(), approved, skipped);
    }
}
