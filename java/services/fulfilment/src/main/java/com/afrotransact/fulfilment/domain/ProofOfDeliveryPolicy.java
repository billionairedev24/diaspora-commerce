package com.afrotransact.fulfilment.domain;

/** Proof-of-delivery rules checked before a drop-off is marked delivered and forwarded to orders-service. */
public interface ProofOfDeliveryPolicy {

    /** Throws {@link FulfilmentRuleViolation} when the proof is not acceptable. */
    PodAssessment check(DeliveryStopFacts stop, ProofOfDelivery proof);
}
