package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.pictures.PictureRulesChange;

/**
 * The ONE picture-rules setting (docs/engineering/picture-rules.md): a named profile plus per-knob overrides, kept in the
 * database, changed only by staff with {@code catalog:picture-rules.manage}, every change audited with a reason. The
 * configured environment default is only the initial value.
 */
public interface PictureRulesService extends PictureRulesProvider {

    /**
     * The rules in force: the staff copy, else the environment default. No permission check (internal callers and the
     * public read); cached for a few seconds and dropped when staff save.
     */
    @Override
    PictureRules current();

    /** What the staff screen shows. Needs {@code catalog:moderate} or {@code catalog:picture-rules.manage}. */
    View view();

    /**
     * Saves the profile and overrides (catalog:picture-rules.manage). 422 for an invalid value or a missing reason;
     * 409 {@code catalog.concurrent-update} when {@code expectedVersion} is not the stored version.
     */
    PictureRules update(PictureProfile profile, PictureOverrides overrides, String reason, long expectedVersion);

    /**
     * The staff view.
     *
     * @param rules     the rules in force
     * @param canChange whether the caller may save
     * @param history   the newest changes first
     * @param defaults  the default knobs of every profile (for the comparison table)
     * @param environmentDefault the profile an environment starts on
     */
    record View(PictureRules rules, boolean canChange, List<PictureRulesChange> history, List<PictureRules> defaults,
                PictureProfile environmentDefault) {
    }
}
