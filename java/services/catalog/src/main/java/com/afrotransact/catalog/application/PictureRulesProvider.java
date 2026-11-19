package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.pictures.PictureRules;

/** The picture rules in force, for the use cases that enforce them (publish gate, image gate, candidate matching). */
@FunctionalInterface
public interface PictureRulesProvider {

    PictureRules current();
}
