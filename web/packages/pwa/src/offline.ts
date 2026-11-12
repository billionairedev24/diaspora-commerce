export interface OfflinePageInput {
  lang: string;
  dir: 'ltr' | 'rtl';
  /** Document title. */
  title: string;
  heading: string;
  body: string;
  /** Label of the "try again" link, which reloads the app's home page. */
  retryLabel: string;
  retryHref: string;
  /** Resolved design token values (from @afrotransact/tokens `resolvedVariables`); no literals here. */
  colors: {
    canvas: string;
    text: string;
    textMuted: string;
    actionPrimary: string;
    actionPrimaryText: string;
  };
  fontFamily: string;
}

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ESCAPES[c] ?? c);
}

/**
 * The offline fallback page: one self-contained document (inline style, no script, no external request) so it renders
 * with nothing else cached. It is public and identical for everyone with the same language.
 */
export function offlineHtml(i: OfflinePageInput): string {
  const c = i.colors;
  const css =
    `*{box-sizing:border-box}html,body{margin:0;min-height:100%}` +
    `body{background:${c.canvas};color:${c.text};font-family:${i.fontFamily};line-height:1.5;-webkit-font-smoothing:antialiased}` +
    `main{min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;padding:24px;text-align:center}` +
    `h1{margin:0;font-size:24px;line-height:1.25}p{margin:0;max-width:34ch;color:${c.textMuted}}` +
    `a{display:inline-flex;align-items:center;justify-content:center;min-height:44px;min-width:44px;padding:0 24px;border-radius:8px;` +
    `background:${c.actionPrimary};color:${c.actionPrimaryText};font-weight:600;text-decoration:none}` +
    `a:focus-visible{outline:3px solid ${c.text};outline-offset:2px}`;
  return (
    `<!doctype html><html lang="${escapeHtml(i.lang)}" dir="${i.dir}"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<meta name="robots" content="noindex"><title>${escapeHtml(i.title)}</title><style>${css}</style></head>` +
    `<body><main><h1>${escapeHtml(i.heading)}</h1><p>${escapeHtml(i.body)}</p>` +
    `<a href="${escapeHtml(i.retryHref)}">${escapeHtml(i.retryLabel)}</a></main></body></html>`
  );
}
