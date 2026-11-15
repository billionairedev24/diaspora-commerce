package com.afrotransact.fakea.application;

import com.afrotransact.fakeb.api.PublishedApi;

/** Fixture: breaks rule 2 (another module's api used outside an adapter or the wiring). */
public class UsesApiInUseCase {

    PublishedApi api;
}
