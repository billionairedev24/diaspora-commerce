package com.afrotransact.fakea.application;

import com.afrotransact.fakeb.domain.Secret;

/** Fixture: breaks rule 1 (uses another module's internals). */
public class ReachesIntoOtherModule {

    Secret secret = new Secret();
}
