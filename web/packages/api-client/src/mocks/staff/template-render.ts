/* The mock BFF's stand-in for notifications' Go renderer: the subset the seeded templates use — {{.field}},
   {{if .field}} … {{else}} … {{end}} and {{- trim -}} — with missingkey=error and HTML escaping of values in email HTML.
   Errors read like Go's ("template render failed: text: … map has no entry for key "x"") so the mock maps them to the
   same 422 fields as the real BFF. */

export type TemplatePart = 'subject' | 'text' | 'html';

export class MockRenderError extends Error {
  constructor(
    readonly part: TemplatePart,
    detail: string
  ) {
    super(`template render failed: ${part}: ${detail}`);
  }
}

const escapeHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&#34;').replace(/'/g, '&#39;');

const truthy = (v: unknown) => !(v === undefined || v === null || v === false || v === '' || v === 0 || (Array.isArray(v) && v.length === 0));

type Token = { kind: 'text'; value: string } | { kind: 'action'; value: string };

function tokenize(part: TemplatePart, src: string): Token[] {
  const out: Token[] = [];
  let i = 0;
  while (i < src.length) {
    const open = src.indexOf('{{', i);
    if (open < 0) {
      out.push({ kind: 'text', value: src.slice(i) });
      break;
    }
    const close = src.indexOf('}}', open + 2);
    if (close < 0) throw new MockRenderError(part, `template: ${part}:1: unclosed action`);
    let text = src.slice(i, open);
    let action = src.slice(open + 2, close);
    if (action.startsWith('-')) {
      text = text.replace(/\s+$/, '');
      action = action.slice(1);
    }
    let next = close + 2;
    if (action.endsWith('-')) {
      action = action.slice(0, -1);
      const rest = src.slice(next);
      next += rest.length - rest.replace(/^\s+/, '').length;
    }
    out.push({ kind: 'text', value: text }, { kind: 'action', value: action.trim() });
    i = next;
  }
  return out;
}

/** Renders one template part with data; throws MockRenderError like the Go renderer would. */
export function renderPart(part: TemplatePart, src: string, data: Record<string, unknown>): string {
  if (!src) return '';
  const lookup = (expr: string): unknown => {
    const m = /^\$?\.([A-Za-z_][A-Za-z0-9_]*)$/.exec(expr.trim());
    if (!m?.[1]) throw new MockRenderError(part, `template: ${part}:1: function "${expr.trim()}" not defined`);
    if (!(m[1] in data)) throw new MockRenderError(part, `template: ${part}:1: executing "${part}" at <.${m[1]}>: map has no entry for key "${m[1]}"`);
    return data[m[1]];
  };
  const out: string[] = [];
  // Each frame: is this branch rendering, and has an earlier branch of the if already rendered?
  const frames: { on: boolean; taken: boolean }[] = [];
  const rendering = () => frames.every((f) => f.on);
  for (const t of tokenize(part, src)) {
    if (t.kind === 'text') {
      if (rendering()) out.push(t.value);
      continue;
    }
    const [word = '', ...rest] = t.value.split(/\s+/);
    if (word === 'if') {
      const on = rendering() && truthy(lookup(rest.join(' ')));
      frames.push({ on, taken: on });
    } else if (word === 'else') {
      const f = frames[frames.length - 1];
      if (!f) throw new MockRenderError(part, `template: ${part}:1: unexpected {{else}}`);
      f.on = !f.taken;
      f.taken = true;
    } else if (word === 'end') {
      if (!frames.pop()) throw new MockRenderError(part, `template: ${part}:1: unexpected {{end}}`);
    } else if (t.value.startsWith('/*')) {
      continue;
    } else if (rendering()) {
      const v = lookup(t.value);
      const s = v === null || v === undefined ? '' : String(v);
      out.push(part === 'html' ? escapeHtml(s) : s);
    }
  }
  if (frames.length) throw new MockRenderError(part, `template: ${part}:1: unexpected EOF`);
  return out.join('');
}
