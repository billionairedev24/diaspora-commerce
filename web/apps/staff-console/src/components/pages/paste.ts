/* Paste-friendly editing: text copied from Word, Google Docs or a web page arrives as HTML; it is turned into the
   Markdown the pages use (## sections, **bold**, *italic*, lists, links, tables) instead of landing as one flat line. */

const SKIP = new Set(['SCRIPT', 'STYLE', 'META', 'LINK', 'HEAD', 'TITLE', 'NOSCRIPT']);

/** Collapses the whitespace a word processor puts inside a text run. */
const squash = (s: string): string => s.replace(/[\s\u00A0]+/g, ' ');

function inline(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return squash(node.textContent ?? '');
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  const el = node as Element;
  if (SKIP.has(el.tagName)) return '';
  const inner = Array.from(el.childNodes).map(inline).join('');
  switch (el.tagName) {
    case 'STRONG':
    case 'B':
      return inner.trim() ? `**${inner.trim()}**` : inner;
    case 'EM':
    case 'I':
      return inner.trim() ? `*${inner.trim()}*` : inner;
    case 'CODE':
      return inner.trim() ? `\`${inner.trim()}\`` : inner;
    case 'BR':
      return '\n';
    case 'A': {
      const href = el.getAttribute('href') ?? '';
      return inner.trim() && /^(https?:\/\/|mailto:|tel:|\/|#)/i.test(href) ? `[${inner.trim()}](${href})` : inner;
    }
    default:
      return inner;
  }
}

function list(el: Element, ordered: boolean, depth: number): string {
  const lines: string[] = [];
  let n = 1;
  for (const child of Array.from(el.children)) {
    if (child.tagName !== 'LI') continue;
    const text = Array.from(child.childNodes)
      .filter((c) => !(c.nodeType === Node.ELEMENT_NODE && ['UL', 'OL'].includes((c as Element).tagName)))
      .map(inline)
      .join('')
      .trim();
    lines.push(`${'  '.repeat(depth)}${ordered ? `${n++}.` : '-'} ${text}`);
    for (const nested of Array.from(child.children)) {
      if (nested.tagName === 'UL' || nested.tagName === 'OL') lines.push(list(nested, nested.tagName === 'OL', depth + 1));
    }
  }
  return lines.join('\n');
}

function table(el: Element): string {
  const rows = Array.from(el.querySelectorAll('tr')).map((tr) => Array.from(tr.children).map((c) => inline(c).trim().replace(/\|/g, '\\|').replace(/\n/g, ' ')));
  if (rows.length === 0) return '';
  const width = Math.max(...rows.map((r) => r.length));
  const pad = (r: string[]) => `| ${Array.from({ length: width }, (_, i) => r[i] ?? '').join(' | ')} |`;
  const [head = [], ...body] = rows;
  return [pad(head), `| ${Array.from({ length: width }, () => '---').join(' | ')} |`, ...body.map(pad)].join('\n');
}

function block(node: Node): string[] {
  if (node.nodeType === Node.TEXT_NODE) {
    const t = squash(node.textContent ?? '').trim();
    return t ? [t] : [];
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return [];
  const el = node as Element;
  if (SKIP.has(el.tagName)) return [];
  switch (el.tagName) {
    case 'H1':
    case 'H2':
      return [`## ${inline(el).trim()}`];
    case 'H3':
    case 'H4':
    case 'H5':
    case 'H6':
      return [`### ${inline(el).trim()}`];
    case 'UL':
    case 'OL':
      return [list(el, el.tagName === 'OL', 0)];
    case 'TABLE':
      return [table(el)];
    case 'HR':
      return ['---'];
    case 'BLOCKQUOTE':
      return [
        inline(el)
          .trim()
          .split('\n')
          .map((l) => `> ${l}`)
          .join('\n')
      ];
    case 'P':
    case 'LI': {
      const t = inline(el).trim();
      return t ? [t] : [];
    }
    case 'DIV':
    case 'SECTION':
    case 'ARTICLE':
    case 'BODY':
    case 'SPAN':
    case 'FONT':
    case 'B':
    case 'STRONG':
    case 'I':
    case 'EM':
    case 'A':
    case 'U': {
      const hasBlocks = Array.from(el.children).some((c) => ['P', 'DIV', 'UL', 'OL', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'TABLE', 'BLOCKQUOTE', 'HR'].includes(c.tagName));
      if (!hasBlocks) {
        const t = inline(el).trim();
        return t ? [t] : [];
      }
      return Array.from(el.childNodes).flatMap(block);
    }
    default:
      return Array.from(el.childNodes).flatMap(block);
  }
}

/** True when the clipboard HTML carries structure worth converting (not just a styled run of plain text). */
export function hasStructure(html: string): boolean {
  return /<(h[1-6]|ul|ol|li|table|blockquote|p|br|strong|b|em|i|a)[\s>]/i.test(html);
}

/** Markdown for a fragment of clipboard HTML. */
export function htmlToMarkdown(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html');
  return block(doc.body)
    .join('\n\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}
