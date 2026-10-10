package com.afrotransact.platform.web.docs;

import org.springframework.web.util.HtmlUtils;

/** Scalar interactive reference (try-it-out console with sandbox tokens in dev and uat). */
public class ScalarApiReferencePage implements ApiReferencePage {

    private final String scalarVersion;

    public ScalarApiReferencePage(String scalarVersion) {
        this.scalarVersion = scalarVersion;
    }

    @Override
    public String path() {
        return "/docs";
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
                <title>%s · API reference</title>
              </head>
              <body>
                <script id="api-reference" data-url="%s"
                        data-configuration='{"theme":"default","layout":"modern","hideDownloadButton":false}'></script>
                <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference@%s"></script>
              </body>
            </html>
            """.formatted(safeTitle, safeSpec, scalarVersion);
    }
}
