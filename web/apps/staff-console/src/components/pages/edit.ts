/* The Markdown editor's toolbar commands as pure functions of the text and the selection, so each one is testable and
   the textarea just applies the result. */

export interface Edit {
  text: string;
  start: number;
  end: number;
}

const lineStart = (text: string, pos: number): number => text.lastIndexOf('\n', pos - 1) + 1;
const lineEnd = (text: string, pos: number): number => {
  const i = text.indexOf('\n', pos);
  return i < 0 ? text.length : i;
};

/** Makes the line a heading of `level` (## or ###); on a line that already has that heading, takes it off. */
export function toggleHeading(e: Edit, level: 2 | 3): Edit {
  const s = lineStart(e.text, e.start);
  const en = lineEnd(e.text, e.start);
  const line = e.text.slice(s, en);
  const prefix = `${'#'.repeat(level)} `;
  const current = /^(#{1,6})\s+/.exec(line);
  const bare = current ? line.slice(current[0].length) : line;
  const next = current && current[1] === '#'.repeat(level) ? bare : `${prefix}${bare}`;
  const delta = next.length - line.length;
  return { text: e.text.slice(0, s) + next + e.text.slice(en), start: Math.max(s, e.start + delta), end: Math.max(s, e.end + delta) };
}

/** Wraps the selection (**bold**, *italic*); wrapped already, it unwraps; empty, it inserts the placeholder selected. */
export function wrap(e: Edit, mark: string, placeholder: string): Edit {
  const sel = e.text.slice(e.start, e.end);
  const before = e.text.slice(Math.max(0, e.start - mark.length), e.start);
  const after = e.text.slice(e.end, e.end + mark.length);
  if (sel && before === mark && after === mark) {
    return { text: e.text.slice(0, e.start - mark.length) + sel + e.text.slice(e.end + mark.length), start: e.start - mark.length, end: e.end - mark.length };
  }
  const inner = sel || placeholder;
  const text = e.text.slice(0, e.start) + mark + inner + mark + e.text.slice(e.end);
  return { text, start: e.start + mark.length, end: e.start + mark.length + inner.length };
}

export type LinePrefix = 'ul' | 'ol' | 'quote';

const MARKERS: Record<LinePrefix, RegExp> = { ul: /^[-*+]\s+/, ol: /^\d+[.)]\s+/, quote: /^>\s?/ };

/** Starts every selected line with a bullet, a number or a quote mark; lines that all have it already lose it. */
export function prefixLines(e: Edit, kind: LinePrefix): Edit {
  const s = lineStart(e.text, e.start);
  const en = lineEnd(e.text, Math.max(e.start, e.end - (e.text[e.end - 1] === '\n' ? 1 : 0)));
  const lines = e.text.slice(s, en).split('\n');
  const allHave = lines.every((l) => MARKERS[kind].test(l));
  const next = lines.map((l, i) => {
    const bare = l.replace(/^([-*+]|\d+[.)]|>)\s*/, '');
    if (allHave) return bare;
    return kind === 'ul' ? `- ${bare}` : kind === 'ol' ? `${i + 1}. ${bare}` : `> ${bare}`;
  });
  const joined = next.join('\n');
  return { text: e.text.slice(0, s) + joined + e.text.slice(en), start: s, end: s + joined.length };
}

/** `[text](https://)` around the selection, with the address selected for typing. */
export function insertLink(e: Edit): Edit {
  const label = e.text.slice(e.start, e.end) || 'link text';
  const url = 'https://';
  const text = `${e.text.slice(0, e.start)}[${label}](${url})${e.text.slice(e.end)}`;
  const urlStart = e.start + label.length + 3;
  return { text, start: urlStart, end: urlStart + url.length };
}

/** A two-column table to type over. */
export function insertTable(e: Edit): Edit {
  const table = '| Column | Column |\n| --- | --- |\n| Text | Text |';
  const needsGap = e.start > 0 && e.text[e.start - 1] !== '\n';
  const lead = needsGap ? '\n\n' : '';
  const text = `${e.text.slice(0, e.start)}${lead}${table}\n${e.text.slice(e.end)}`;
  const first = e.start + lead.length + 2;
  return { text, start: first, end: first + 'Column'.length };
}

/** A link to a section of the same page, `[Heading](#anchor)`. */
export function insertSectionLink(e: Edit, heading: { id: string; title: string }): Edit {
  const label = e.text.slice(e.start, e.end) || heading.title;
  const link = `[${label}](#${heading.id})`;
  return { text: e.text.slice(0, e.start) + link + e.text.slice(e.end), start: e.start + link.length, end: e.start + link.length };
}

/** The offset of the start of a 1-based line. */
export function offsetOfLine(text: string, line: number): number {
  let offset = 0;
  for (let i = 1; i < line; i++) {
    const next = text.indexOf('\n', offset);
    if (next < 0) return text.length;
    offset = next + 1;
  }
  return offset;
}
