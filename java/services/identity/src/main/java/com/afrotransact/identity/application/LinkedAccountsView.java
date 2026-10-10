package com.afrotransact.identity.application;

import java.util.List;

import com.afrotransact.identity.domain.FederatedIdentity;
import com.afrotransact.identity.domain.SignInMethods;

/** Linked social identities plus every sign-in method (so the UI can disable the last "Unlink"). */
public record LinkedAccountsView(List<FederatedIdentity> identities, SignInMethods methods) {
}
