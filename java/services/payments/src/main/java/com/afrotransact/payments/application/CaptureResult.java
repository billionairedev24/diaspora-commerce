package com.afrotransact.payments.application;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.Payment;

/** Result of a capture (replayed = the shipment was already captured). */
public record CaptureResult(Capture capture, Payment payment, boolean replayed) {
}
