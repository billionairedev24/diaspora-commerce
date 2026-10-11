import { Fragment, type ReactNode } from 'react';

/* Legal documents are Markdown from content-service (`# Title`, `## Section`, paragraphs, `-` / `1.` lists, **bold**,
   [links](…)). This renders exactly that subset to React elements — never HTML strings — so a document can't inject
   markup. Email addresses become mailto: links. */

export type LegalBlock = { kind: 'p'; text: string } | { kind: 'ul' | 'ol'; items: string[] };

export interface LegalSection {
  id: string;
  title: string;
  blocks: LegalBlock[];
}

export interface ParsedLegalDocument {
  /** Text before the first `##` heading (after the `#` title). */
  intro: LegalBlock[];
  sections: LegalSection[];
}

/** Anchor id for a heading; content-service's outline ids take precedence when given. */
export const headingId = (title: string): string =>
  title
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-');

export function parseLegalMarkdown(body: string, outline: ReadonlyArray<{ id: string; title: string }> = []): ParsedLegalDocument {
  const intro: LegalBlock[] = [];
  const sections: LegalSection[] = [];
  let para: string[] = [];
  let list: { kind: 'ul' | 'ol'; items: string[] } | null = null;
  const target = () => (sections.length ? (sections[sections.length - 1] as LegalSection).blocks : intro);
  const flush = () => {
    if (para.length) target().push({ kind: 'p', text: para.join(' ') });
    para = [];
    if (list) target().push(list);
    list = null;
  };
  for (const raw of body.replace(/\r\n?/g, '\n').split('\n')) {
    const line = raw.trim();
    if (!line) {
      flush();
      continue;
    }
    if (/^#\s/.test(line)) {
      flush();
      continue; // the document title is the page's <h1>
    }
    const h = /^#{2,3}\s+(.+)$/.exec(line);
    if (h) {
      flush();
      const title = (h[1] ?? '').trim();
      const fromOutline = outline[sections.length];
      sections.push({ id: fromOutline && fromOutline.title === title ? fromOutline.id : headingId(title), title, blocks: [] });
      continue;
    }
    const li = /^(?:([-*])|(\d+)[.)])\s+(.+)$/.exec(line);
    if (li) {
      if (para.length) {
        target().push({ kind: 'p', text: para.join(' ') });
        para = [];
      }
      const kind = li[1] ? 'ul' : 'ol';
      if (!list || list.kind !== kind) {
        if (list) target().push(list);
        list = { kind, items: [] };
      }
      list.items.push(li[3] ?? '');
      continue;
    }
    if (list) {
      target().push(list);
      list = null;
    }
    para.push(line);
  }
  flush();
  return { intro, sections };
}

const INLINE = /\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)\s]+)\)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})/g;

/** Only same-site paths, https, mailto: and tel: links are rendered as links. */
export function safeHref(href: string): string | null {
  if (href.startsWith('/') && !href.startsWith('//')) return href;
  if (/^(https:\/\/|mailto:|tel:)/i.test(href)) return href;
  return null;
}

/** Renders the inline subset: **bold**, [text](href) and email addresses. */
export function renderInline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let n = 0;
  for (const m of text.matchAll(INLINE)) {
    const at = m.index;
    if (at > last) out.push(text.slice(last, at));
    if (m[1] !== undefined) out.push(<strong key={n++}>{m[1]}</strong>);
    else if (m[2] !== undefined) {
      const href = safeHref(m[3] ?? '');
      out.push(href ? <a key={n++} href={href}>{m[2]}</a> : <Fragment key={n++}>{m[2]}</Fragment>);
    } else if (m[4] !== undefined) out.push(<a key={n++} href={`mailto:${m[4]}`}>{m[4]}</a>);
    last = at + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function LegalBlocks({ blocks, className }: { blocks: LegalBlock[]; className?: string }) {
  return blocks.map((b, i) => {
    if (b.kind === 'p') {
      return (
        <p key={i} className={className}>
          {renderInline(b.text)}
        </p>
      );
    }
    const List = b.kind;
    return (
      <List key={i} className={className}>
        {b.items.map((it, j) => (
          <li key={j}>{renderInline(it)}</li>
        ))}
      </List>
    );
  });
}
