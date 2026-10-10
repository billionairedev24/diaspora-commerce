package com.afrotransact.platform.web.docs;

/**
 * Renders an interactive API reference page for the service's OpenAPI document.
 * Implementations: {@link ScalarApiReferencePage} (served at /docs) and {@link RedocApiReferencePage} (served at /redoc).
 */
public interface ApiReferencePage {

    /** Path the page is served on, for example {@code /docs}. */
    String path();

    /** Full HTML document for the page. */
    String render(String title, String specUrl);
}
