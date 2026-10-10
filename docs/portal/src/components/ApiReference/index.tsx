import BrowserOnly from '@docusaurus/BrowserOnly';
import useBaseUrl from '@docusaurus/useBaseUrl';
import clsx from 'clsx';
import { type ReactNode, useState } from 'react';

import styles from './styles.module.css';

// Same pinned renderers as java/libs/platform-web (ApiDocsProperties: scalarVersion / redocVersion).
export const SCALAR_VERSION = '1.73.1';
export const REDOC_VERSION = '2.5.4';

type View = 'redoc' | 'scalar';

export interface ApiReferenceProps {
  name: string;
  title: string;
  version?: string;
  specUrl: string;
  summary?: string;
  operations?: number;
  paths?: number;
  servers?: string[];
  error?: string | null;
  sourceUrl?: string;
}

/** Reads a design token (CSS custom property) so the embedded renderers use the brand palette. */
function token(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}

/** JSON that is safe to inline inside a <script> element. */
const inlineJson = (value: unknown): string => JSON.stringify(value).replace(/</g, '\\u003c');
const attr = (value: string): string => value.replace(/&/g, '&amp;').replace(/'/g, '&#39;').replace(/"/g, '&quot;');

function frameHead(fontsHref: string): string {
  const canvas = token('--at-color-canvas', 'white');
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="${attr(fontsHref)}">
<style>html,body{margin:0;background:${canvas};font-family:Figtree,ui-sans-serif,system-ui,sans-serif}
*{scrollbar-width:none;-ms-overflow-style:none}*::-webkit-scrollbar{display:none;width:0;height:0}
.at-cdn-error{margin:48px auto;max-width:560px;padding:16px 20px;border-radius:12px;background:${token('--at-color-danger-bg', 'mistyrose')};color:${token('--at-color-danger', 'firebrick')}}</style></head><body>`;
}

/** Shown inside the frame when the pinned renderer cannot be fetched from the CDN (offline, blocked egress). */
function cdnFallback(renderer: string, specHref: string): string {
  const html = `<div class="at-cdn-error">Could not load ${renderer} from cdn.jsdelivr.net. <a href="${attr(specHref)}" download>Download the contract</a> instead.</div>`;
  return `<script>window.addEventListener('error',function(e){var t=e.target;if(t&&t.tagName==='SCRIPT'&&t.src){document.body.innerHTML=${inlineJson(html)};}},true);</script>`;
}

function redocDocument(specHref: string, fontsHref: string): string {
  const options = {
    hideDownloadButton: false,
    expandResponses: '200,201',
    pathInMiddlePanel: true,
    scrollYOffset: 0,
    theme: {
      colors: {
        primary: { main: token('--at-color-link', 'darkgreen') },
        success: { main: token('--at-color-success', 'green') },
        error: { main: token('--at-color-danger', 'firebrick') },
        text: { primary: token('--at-color-text', 'black') },
      },
      typography: {
        fontFamily: 'Figtree, ui-sans-serif, system-ui, sans-serif',
        headings: { fontFamily: 'Figtree, ui-sans-serif, system-ui, sans-serif', fontWeight: '700' },
        code: { fontFamily: token('--at-font-mono', 'monospace') },
      },
      sidebar: { backgroundColor: token('--at-color-surface-muted', 'whitesmoke') },
      rightPanel: { backgroundColor: token('--at-ink-900', 'black') },
    },
  };
  return `${frameHead(fontsHref)}${cdnFallback(`Redoc ${REDOC_VERSION}`, specHref)}<div id="redoc"></div>
<script src="https://cdn.jsdelivr.net/npm/redoc@${REDOC_VERSION}/bundles/redoc.standalone.js"></script>
<script>if(window.Redoc)Redoc.init(${inlineJson(specHref)}, ${inlineJson(options)}, document.getElementById('redoc'));</script></body></html>`;
}

function scalarDocument(specHref: string, fontsHref: string): string {
  const configuration = {
    theme: 'default',
    layout: 'modern',
    hideDownloadButton: false,
    hideDarkModeToggle: true,
    forceDarkModeState: 'light',
    withDefaultFonts: false,
    customCss: `.scalar-app,.light-mode{--scalar-font:Figtree,ui-sans-serif,system-ui,sans-serif;--scalar-color-accent:${token('--at-color-link', 'darkgreen')}}`,
  };
  return `${frameHead(fontsHref)}${cdnFallback(`Scalar ${SCALAR_VERSION}`, specHref)}<script id="api-reference" data-url="${attr(specHref)}" data-configuration='${attr(JSON.stringify(configuration))}'></script>
<script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference@${SCALAR_VERSION}"></script></body></html>`;
}

function Frame({ view, specPath, fontsPath, title }: { view: View; specPath: string; fontsPath: string; title: string }): ReactNode {
  const specHref = new URL(specPath, window.location.origin).href;
  const fontsHref = new URL(fontsPath, window.location.origin).href;
  const srcDoc = view === 'redoc' ? redocDocument(specHref, fontsHref) : scalarDocument(specHref, fontsHref);
  return <iframe key={view} className={styles.frame} title={`${title} — ${view === 'redoc' ? 'Redoc' : 'Scalar'}`} srcDoc={srcDoc} />;
}

export default function ApiReference(props: ApiReferenceProps): ReactNode {
  const { name, title, version, specUrl, summary, operations, paths, servers, error } = props;
  const [view, setView] = useState<View>('redoc');
  const specPath = useBaseUrl(specUrl);
  const fontsPath = useBaseUrl('/fonts/figtree.css');
  const file = specUrl.split('/').pop() ?? `${name}.yaml`;

  return (
    <section className={styles.wrapper}>
      <header className={styles.header}>
        <div className={styles.meta}>
          {version ? <span className={styles.badge}>{/^\d/.test(version) ? `v${version}` : version}</span> : null}
          <span className={styles.badgeMuted}>OpenAPI · {operations ?? 0} operations · {paths ?? 0} paths</span>
          {servers?.length ? <span className={styles.badgeMuted}>{servers.join(' · ')}</span> : null}
        </div>
        {summary ? <p className={styles.summary}>{summary}</p> : null}
        {error ? <p className={styles.error}>This contract does not parse ({error}); the renderers below may fail.</p> : null}
        <div className={styles.toolbar}>
          <div className={styles.tabs} role="tablist" aria-label="Renderer">
            {(['redoc', 'scalar'] as const).map((v) => (
              <button
                key={v}
                type="button"
                role="tab"
                aria-selected={view === v}
                className={clsx(styles.tab, view === v && styles.tabActive)}
                onClick={() => setView(v)}
              >
                {v === 'redoc' ? `Redoc ${REDOC_VERSION}` : `Scalar ${SCALAR_VERSION}`}
              </button>
            ))}
          </div>
          <div className={styles.actions}>
            <a className={styles.download} href={specPath} download={file}>
              Download {file}
            </a>
            <a className={styles.link} href={specPath} target="_blank" rel="noreferrer">
              Open raw
            </a>
          </div>
        </div>
      </header>
      <BrowserOnly fallback={<div className={styles.placeholder}>Loading the {title} reference…</div>}>
        {() => <Frame view={view} specPath={specPath} fontsPath={fontsPath} title={title} />}
      </BrowserOnly>
    </section>
  );
}
