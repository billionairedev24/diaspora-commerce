package com.afrotransact.identity.adapter.in.web;

import org.springframework.web.util.HtmlUtils;

/** Minimal, dependency-free page shell for the authorization server's own screens (sign-in, two-step). */
final class HtmlPage {

    private HtmlPage() {
    }

    static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }

    static String render(String title, String body, String scripts) {
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s · AfroTransact</title>
                <style>
                  :root { color-scheme: light; font-family: Figtree, system-ui, sans-serif; }
                  body { margin: 0; background: #fff; color: #1a1a1a; }
                  main { max-width: 26rem; margin: 3rem auto; padding: 0 1rem; }
                  h1 { font-size: 1.5rem; margin: 0 0 1rem; }
                  label { display: block; margin: .75rem 0 .25rem; font-weight: 600; }
                  input[type=email], input[type=password], input[type=text] {
                    width: 100%%; box-sizing: border-box; padding: .65rem; border: 1px solid #c9c9c9; border-radius: .5rem; }
                  button, .btn { display: block; width: 100%%; box-sizing: border-box; margin-top: 1rem; padding: .7rem;
                    border-radius: .5rem; border: 1px solid #1a1a1a; background: #1a1a1a; color: #fff; font-weight: 600;
                    text-align: center; text-decoration: none; cursor: pointer; }
                  .secondary { background: #fff; color: #1a1a1a; }
                  .error { color: #b42318; } .note { color: #555; font-size: .9rem; }
                  .divider { margin: 1.5rem 0 .5rem; color: #666; text-align: center; }
                </style>
              </head>
              <body><main>%s</main>%s</body>
            </html>
            """.formatted(esc(title), body, scripts);
    }
}
