package com.afrotransact.identity.application;

import java.util.List;

/**
 * A forced enrolment finished: the sign-in continues with {@code step} (a login ticket); {@code recoveryCodes} are
 * shown once when this was the person's first factor.
 */
public record EnrolmentCompleted(List<String> recoveryCodes, SignInStep step) {
}
