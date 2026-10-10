package com.afrotransact.platform.web.docs;

import org.springframework.web.util.HtmlUtils;

/** Redoc static three-panel reference; the same renderer is used for the versioned docs on the portal. */
public class RedocApiReferencePage implements ApiReferencePage {

    private final String redocVersion;

    public RedocApiReferencePage(String redocVersion) {
        this.redocVersion = redocVersion;
    }

    @Override
    public String path() {
        return "/redoc";
    }

    @Override
    public String render(String title, String specUrl) {
        String safeTitle = HtmlUtils.htmlEscape(title);
        String safeSpec = HtmlUtils.htmlEscape(specUrl);
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s · Redoc</title>
                <style>body { margin: 0; }</style>
              </head>
              <body>
                <redoc spec-url="%s" hide-hostname="true" path-in-middle-panel="true"></redoc>
                <script src="https://cdn.jsdelivr.net/npm/redoc@%s/bundles/redoc.standalone.js"></script>
              </body>
            </html>
            """.formatted(safeTitle, safeSpec, redocVersion);
    }
}
