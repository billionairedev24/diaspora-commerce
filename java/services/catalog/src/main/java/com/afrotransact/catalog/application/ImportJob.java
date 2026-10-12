package com.afrotransact.catalog.application;

import java.util.UUID;

/** Imports a started bulk upload product by product until every importable row has an outcome. */
interface ImportJob {

    /** Runs (or resumes) the upload; returns when it is complete or another pod holds it. Safe to call repeatedly. */
    void drive(UUID importId);
}
