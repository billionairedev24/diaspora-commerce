package com.afrotransact.catalog.domain;

import java.util.List;

/** The automatic checks a new or edited review passes before it can be published without a human (S324). */
public interface ReviewContentPolicy {

    /**
     * Screens the free text of a review.
     *
     * @return what was found, in a stable order; empty when the review may be published straight away
     */
    List<ReviewFlag> screen(String title, String text);
}
