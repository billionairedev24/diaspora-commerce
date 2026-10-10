// Semgrep rule fixtures for tools/security/semgrep.yml.
import DOMPurify from 'dompurify';

export function Bad({ html }: { html: string }) {
  // ruleid: at.ts.dangerously-set-inner-html-unsanitized
  return <div dangerouslySetInnerHTML={{ __html: html }} />;
}

export function Good({ html }: { html: string }) {
  // ok: at.ts.dangerously-set-inner-html-unsanitized
  return <div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(html) }} />;
}

export function JsonLd({ data }: { data: object }) {
  // ok: at.ts.dangerously-set-inner-html-unsanitized
  return <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(data).replace(/</g, '\\u003c') }} />;
}

export function store(token: string) {
  // ruleid: at.ts.token-in-web-storage
  localStorage.setItem('accessToken', token);
  // ok: at.ts.token-in-web-storage
  localStorage.setItem('recentSearches', '[]');
}
