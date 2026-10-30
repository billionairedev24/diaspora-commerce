import { countLegalMarkers } from './markdown';
import { outlineOf, placeholdersUsed, slugOf } from './text';

/* The checks that keep a page publishable. The editor runs them while typing; the BFF repeats the ones that matter
   (English present, no repeated section names, links that go somewhere) and content-service the rest on publication. */

export type IssueLevel = 'error' | 'warning';

export type IssueCode = 'empty' | 'duplicate-heading' | 'broken-link' | 'broken-anchor' | 'legal-marker' | 'unknown-placeholder' | 'text-before-heading' | 'empty-heading';

export interface ContentIssue {
  level: IssueLevel;
  code: IssueCode;
  /** The offending text (a heading, a link target, a token). */
  subject: string;
  /** 1-based line, when the issue sits on one. */
  line?: number;
  /** For `legal-marker`: how many markers are left. */
  count?: number;
}

export interface ValidateOptions {
  /** Legal documents start with a `#` title; help pages with a `## ` heading. */
  kind: 'legal' | 'help';
  /** Publishing as final: counsel markers are errors (otherwise a warning). */
  final: boolean;
  /** Values for `{tokens}`; a token with no value is flagged (legal only). */
  placeholders?: Readonly<Record<string, string>>;
}

const LINK = /\[[^\]]*\]\(([^)\s]*)[^)]*\)/g;
const SCHEME = /^(https?:\/\/|mailto:|tel:|\/(?!\/)|#)/i;

/** Problems in one Markdown text. Nothing here needs a server. */
export function validateMarkdown(markdown: string, opts: ValidateOptions): ContentIssue[] {
  const issues: ContentIssue[] = [];
  if (markdown.trim() === '') return [{ level: 'error', code: 'empty', subject: '' }];
  const lines = markdown.split('\n');
  const seen = new Map<string, number>();
  let firstHeading = -1;
  lines.forEach((raw, i) => {
    const m = /^##\s+(.*?)\s*$/.exec(raw);
    if (!m) return;
    const title = m[1] ?? '';
    if (firstHeading < 0) firstHeading = i;
    if (title === '') {
      issues.push({ level: 'error', code: 'empty-heading', subject: '', line: i + 1 });
      return;
    }
    const key = title.toLowerCase();
    if (seen.has(key)) issues.push({ level: 'error', code: 'duplicate-heading', subject: title, line: i + 1 });
    else seen.set(key, i + 1);
  });
  if (opts.kind === 'help') {
    const before = lines.findIndex((l) => l.trim() !== '');
    if (before >= 0 && !/^##\s/.test(lines[before] ?? '')) issues.push({ level: 'error', code: 'text-before-heading', subject: lines[before]?.trim() ?? '', line: before + 1 });
  }
  const anchors = new Set(outlineOf(markdown).map((o) => o.id));
  lines.forEach((raw, i) => {
    for (const m of raw.matchAll(LINK)) {
      const dest = m[1] ?? '';
      if (dest === '' || !SCHEME.test(dest)) issues.push({ level: 'error', code: 'broken-link', subject: dest, line: i + 1 });
      else if (dest.startsWith('#') && !anchors.has(dest.slice(1)) && !anchors.has(slugOf(dest.slice(1)))) {
        issues.push({ level: 'error', code: 'broken-anchor', subject: dest, line: i + 1 });
      }
    }
  });
  const markers = countLegalMarkers(markdown);
  if (markers > 0) issues.push({ level: opts.final ? 'error' : 'warning', code: 'legal-marker', subject: '[[LEGAL: …]]', count: markers });
  if (opts.kind === 'legal' && opts.placeholders) {
    for (const token of placeholdersUsed(markdown)) {
      if (opts.placeholders[token] === undefined) issues.push({ level: 'warning', code: 'unknown-placeholder', subject: `{${token}}` });
    }
  }
  return issues;
}

export const errorsOf = (issues: readonly ContentIssue[]): ContentIssue[] => issues.filter((i) => i.level === 'error');
