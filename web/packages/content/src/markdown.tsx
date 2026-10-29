import { Fragment, type ReactNode } from 'react';

/* Shared by the storefront (which shows the page) and the staff console (which previews and edits it), so the preview is
   the page. Legal documents are Markdown from content-service (`# Title`, `## Section`, `###` sub-headings, paragraphs, `-` / `1.`
   lists with one nested level, pipe tables, `>` quotes, `---` rules, **bold**, *italic*, `code`, [links](…)). This
   renders exactly that subset to React elements — never HTML strings — so a document can't inject markup. Email
   addresses become mailto: links. */

export type Align = 'left' | 'right' | 'center' | null;

export type LegalBlock =
  | { kind: 'p'; text: string }
  | { kind: 'ul' | 'ol'; items: string[]; children?: Array<LegalBlock | undefined> }
  | { kind: 'h3' | 'h4'; text: string; id: string }
  | { kind: 'quote'; text: string }
  | { kind: 'hr' }
  | { kind: 'table'; head: string[]; rows: string[][]; align: Align[] };

export interface LegalSection {
  id: string;
  /** Heading text as shown (a leading "1." / "2.1" is dropped: the page numbers its sections itself). */
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

/** "2. Definitions" / "2.1) Scope" → "Definitions" / "Scope". */
const stripNumbering = (title: string): string => title.replace(/^\d+(?:\.\d+)*[.)]?\s+(?=\S)/, '');

const LIST_ITEM = /^(\s*)(?:([-*+])|(\d+)[.)])\s+(.+)$/;
const TABLE_ROW = /^\|.*\|\s*$/;
const TABLE_RULE = /^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

const cells = (row: string): string[] =>
  row
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());

const alignOf = (cell: string): Align => {
  const left = cell.startsWith(':');
  const right = cell.endsWith(':');
  return left && right ? 'center' : right ? 'right' : left ? 'left' : null;
};

export function parseLegalMarkdown(body: string, outline: ReadonlyArray<{ id: string; title: string }> = []): ParsedLegalDocument {
  const intro: LegalBlock[] = [];
  const sections: LegalSection[] = [];
  const used = new Set<string>(outline.map((o) => o.id));
  const unique = (id: string): string => {
    let candidate = id || 'section';
    for (let n = 2; used.has(candidate); n++) candidate = `${id || 'section'}-${n}`;
    used.add(candidate);
    return candidate;
  };
  let para: string[] = [];
  let quote: string[] = [];
  let list: Extract<LegalBlock, { kind: 'ul' | 'ol' }> | null = null;
  let table: { head: string[]; align: Align[]; rows: string[][] } | null = null;
  let headings = 0;
  const target = () => (sections.length ? (sections[sections.length - 1] as LegalSection).blocks : intro);
  const flush = () => {
    if (para.length) target().push({ kind: 'p', text: para.join(' ') });
    para = [];
    if (quote.length) target().push({ kind: 'quote', text: quote.join(' ') });
    quote = [];
    if (list) target().push(list);
    list = null;
    if (table) target().push({ kind: 'table', ...table });
    table = null;
  };
  const lines = body.replace(/\r\n?/g, '\n').split('\n');
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i] ?? '';
    const line = raw.trim();
    if (!line) {
      flush();
      continue;
    }
    if (/^#\s/.test(line)) {
      flush();
      continue; // the document title is the page's <h1>
    }
    const h2 = /^##\s+(.+)$/.exec(line);
    if (h2) {
      flush();
      const original = (h2[1] ?? '').trim();
      const fromOutline = outline[headings++];
      const id = fromOutline && fromOutline.title === original ? fromOutline.id : unique(headingId(original));
      sections.push({ id, title: stripNumbering(original), blocks: [] });
      continue;
    }
    const sub = /^(#{3,6})\s+(.+)$/.exec(line);
    if (sub) {
      flush();
      const text = stripNumbering((sub[2] ?? '').trim());
      target().push({ kind: sub[1] === '###' ? 'h3' : 'h4', text, id: unique(headingId(text)) });
      continue;
    }
    if (/^(?:-{3,}|\*{3,}|_{3,})$/.test(line)) {
      flush();
      target().push({ kind: 'hr' });
      continue;
    }
    if (TABLE_ROW.test(line) && !table && TABLE_RULE.test((lines[i + 1] ?? '').trim())) {
      flush();
      const align = cells(lines[i + 1] ?? '').map(alignOf);
      table = { head: cells(line), align, rows: [] };
      i += 1;
      continue;
    }
    if (table) {
      if (TABLE_ROW.test(line)) {
        const row = cells(line);
        const width = table.head.length;
        table.rows.push(Array.from({ length: width }, (_, c) => row[c] ?? ''));
        continue;
      }
      flush();
    }
    if (line.startsWith('>')) {
      if (para.length || list) flush();
      quote.push(line.replace(/^>\s?/, ''));
      continue;
    }
    if (quote.length) flush();
    const li = LIST_ITEM.exec(raw);
    if (li) {
      if (para.length) {
        target().push({ kind: 'p', text: para.join(' ') });
        para = [];
      }
      const nested = (li[1] ?? '').length >= 2 && list !== null;
      const kind = li[2] ? 'ul' : 'ol';
      const text = li[4] ?? '';
      if (nested && list) {
        const at = list.items.length - 1;
        const children = (list.children ??= []);
        const parent = children[at];
        if (parent && (parent.kind === 'ul' || parent.kind === 'ol')) parent.items.push(text);
        else children[at] = { kind, items: [text] };
        continue;
      }
      if (!list || list.kind !== kind) {
        if (list) target().push(list);
        list = { kind, items: [] };
      }
      list.items.push(text);
      continue;
    }
    if (list && /^\s{2,}\S/.test(raw)) {
      // A wrapped continuation line of the previous list item.
      const at = list.items.length - 1;
      list.items[at] = `${list.items[at] ?? ''} ${line}`;
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

const INLINE = /`([^`]+)`|\*\*(.+?)\*\*|(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])|(?<![\w_])_([^_\s][^_]*?)_(?![\w_])|\[([^\]]+)\]\(([^)\s]+)\)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})/g;

/** Only same-site paths, https, mailto: and tel: links are rendered as links. */
export function safeHref(href: string): string | null {
  if (href.startsWith('/') && !href.startsWith('//')) return href;
  if (/^(https:\/\/|mailto:|tel:)/i.test(href)) return href;
  return null;
}

/** `[[LEGAL: …]]`: a point counsel still has to decide. Shoppers never see one in a published final document. */
export const LEGAL_MARKER = /\[\[LEGAL:[^\]]*\]\]/g;

/** How many unresolved `[[LEGAL: …]]` markers a text holds. */
export const countLegalMarkers = (text: string): number => text.match(LEGAL_MARKER)?.length ?? 0;

/** When set, `[[LEGAL: …]]` markers render as a highlighted `<mark data-legal-marker>` (the staff preview). */
export interface MarkerStyle {
  className?: string;
}

function plain(text: string, marks: MarkerStyle | undefined, key: () => number): ReactNode[] {
  if (!marks) return [text];
  const out: ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(LEGAL_MARKER)) {
    if (m.index > last) out.push(text.slice(last, m.index));
    out.push(
      <mark key={`m${key()}`} data-legal-marker="" className={marks.className}>
        {m[0]}
      </mark>
    );
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** Renders the inline subset: `code`, **bold**, *italic*, [text](href) and email addresses. */
export function renderInline(text: string, marks?: MarkerStyle): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let n = 0;
  const key = () => n++;
  for (const m of text.matchAll(INLINE)) {
    const at = m.index;
    if (at > last) out.push(...plain(text.slice(last, at), marks, key));
    if (m[1] !== undefined) out.push(<code key={n++}>{m[1]}</code>);
    else if (m[2] !== undefined) out.push(<strong key={n++}>{renderInline(m[2], marks)}</strong>);
    else if (m[3] !== undefined) out.push(<em key={n++}>{m[3]}</em>);
    else if (m[4] !== undefined) out.push(<em key={n++}>{m[4]}</em>);
    else if (m[5] !== undefined) {
      const href = safeHref(m[6] ?? '');
      out.push(href ? <a key={n++} href={href}>{m[5]}</a> : <Fragment key={n++}>{m[5]}</Fragment>);
    } else if (m[7] !== undefined) out.push(<a key={n++} href={`mailto:${m[7]}`}>{m[7]}</a>);
    last = at + m[0].length;
  }
  if (last < text.length) out.push(...plain(text.slice(last), marks, key));
  return out;
}

export interface LegalBlockClasses {
  /** Paragraphs and lists. */
  body?: string;
  subheading?: string;
  quote?: string;
  tableWrap?: string;
  table?: string;
  cell?: string;
  rule?: string;
  /** Class of a highlighted `[[LEGAL: …]]` marker. */
  marker?: string;
  /** Render `[[LEGAL: …]]` markers highlighted (staff preview only). */
  highlightMarkers?: boolean;
}

function Block({ block, classes }: { block: LegalBlock; classes: LegalBlockClasses }) {
  const marks: MarkerStyle | undefined = classes.highlightMarkers ? { className: classes.marker } : undefined;
  const inline = (text: string) => renderInline(text, marks);
  switch (block.kind) {
    case 'p':
      return <p className={classes.body}>{inline(block.text)}</p>;
    case 'h3':
      return (
        <h3 id={block.id} className={classes.subheading}>
          {block.text}
        </h3>
      );
    case 'h4':
      return (
        <h4 id={block.id} className={classes.subheading}>
          {block.text}
        </h4>
      );
    case 'quote':
      return <blockquote className={classes.quote}>{inline(block.text)}</blockquote>;
    case 'hr':
      return <hr className={classes.rule} />;
    case 'table':
      return (
        <div className={classes.tableWrap}>
          <table className={classes.table}>
            <thead>
              <tr>
                {block.head.map((h, c) => (
                  <th key={c} scope="col" className={classes.cell} style={block.align[c] ? { textAlign: block.align[c] } : undefined}>
                    {inline(h)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, r) => (
                <tr key={r}>
                  {row.map((cell, c) => (
                    <td key={c} className={classes.cell} style={block.align[c] ? { textAlign: block.align[c] } : undefined}>
                      {inline(cell)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'ul':
    case 'ol': {
      const List = block.kind;
      return (
        <List className={classes.body}>
          {block.items.map((it, j) => {
            const child = block.children?.[j];
            return (
              <li key={j}>
                {inline(it)}
                {child && <Block block={child} classes={classes} />}
              </li>
            );
          })}
        </List>
      );
    }
  }
}

export function LegalBlocks({ blocks, className, classes }: { blocks: LegalBlock[]; className?: string; classes?: LegalBlockClasses }) {
  const merged: LegalBlockClasses = { body: className, ...classes };
  return blocks.map((b, i) => <Block key={i} block={b} classes={merged} />);
}
