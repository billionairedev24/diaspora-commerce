import { type ContentDocument, type ContentDraftInput, type ContentKind, type ContentLocale, type ContentPage } from '@afrotransact/api-client';
import { type ContentIssue, validateMarkdown } from '@afrotransact/content';

/** What the editor holds while a person types: the draft, or the published text it starts from. */
export interface EditorForm {
  version: string;
  effectiveDate: string;
  draftBanner: boolean;
  title: string;
  summary: string;
  topic: string;
  sortOrder: number;
  bodies: Record<string, string>;
  needsReview: string[];
  changeSummary: string;
}

/** "3.2" → "3.3": the next number a new version of a legal document gets. */
export function nextMinor(version: string): string {
  const [major = '1', minor = '0'] = version.split('.');
  return `${Number(major)}.${Number(minor) + 1}`;
}

/** The form the editor opens with: the saved draft, or the published page with the next version number. */
export function initialForm(page: ContentPage): EditorForm {
  const source: ContentDocument = page.draft ?? page.published;
  const legal = page.kind === 'legal';
  return {
    version: legal ? (page.draft ? page.draft.version : nextMinor(page.published.version)) : '',
    effectiveDate: source.effectiveDate ?? '',
    draftBanner: source.draftBanner ?? true,
    title: legal ? page.title : source.title,
    summary: source.summary ?? '',
    topic: source.topic ?? '',
    sortOrder: source.sortOrder ?? 0,
    bodies: { ...source.bodies },
    needsReview: [...source.needsReview],
    changeSummary: page.draft?.changeSummary ?? ''
  };
}

/** The form of an older version or a past draft, for restoring text into the editor. */
export function formOf(doc: ContentDocument, title: string, version: string): EditorForm {
  return {
    version,
    effectiveDate: doc.effectiveDate ?? '',
    draftBanner: doc.draftBanner ?? true,
    title: doc.title || title,
    summary: doc.summary ?? '',
    topic: doc.topic ?? '',
    sortOrder: doc.sortOrder ?? 0,
    bodies: { ...doc.bodies },
    needsReview: [...doc.needsReview],
    changeSummary: ''
  };
}

const canon = (f: EditorForm): string =>
  JSON.stringify({ ...f, bodies: Object.fromEntries(Object.entries(f.bodies).sort(([a], [b]) => a.localeCompare(b))), needsReview: [...f.needsReview].sort() });

/** A form with no change cannot be saved. */
export const isDirty = (current: EditorForm, baseline: EditorForm): boolean => canon(current) !== canon(baseline);

/** The request body of a save. */
export function toInput(kind: ContentKind, f: EditorForm, rowVersion: number | null): ContentDraftInput {
  const input: ContentDraftInput = { bodies: f.bodies, needsReview: f.needsReview.filter((l) => f.bodies[l] !== undefined), changeSummary: f.changeSummary.trim() };
  if (rowVersion !== null) input.rowVersion = rowVersion;
  if (kind === 'legal') {
    input.version = f.version.trim();
    input.effectiveDate = f.effectiveDate;
    input.draftBanner = f.draftBanner;
  } else {
    input.title = f.title.trim();
    input.summary = f.summary.trim();
    input.topic = f.topic;
    input.sortOrder = f.sortOrder;
  }
  return input;
}

/** The checks of every language of the form: [language, issues]. */
export function checkForm(kind: ContentKind, f: EditorForm, placeholders: Readonly<Record<string, string>>): Record<string, ContentIssue[]> {
  const out: Record<string, ContentIssue[]> = {};
  for (const [locale, text] of Object.entries(f.bodies)) {
    out[locale] = validateMarkdown(text, { kind, final: kind === 'legal' && !f.draftBanner, placeholders: kind === 'legal' ? placeholders : undefined });
  }
  return out;
}

/** Field problems of the form that are not about the Markdown (shown next to their fields). */
export function fieldProblems(kind: ContentKind, f: EditorForm): Record<string, 'required' | 'version'> {
  const out: Record<string, 'required' | 'version'> = {};
  if (kind === 'legal') {
    if (!/^[0-9]{1,3}\.[0-9]{1,3}(\.[0-9]{1,3})?$/.test(f.version.trim())) out.version = 'version';
    if (!f.effectiveDate) out.effectiveDate = 'required';
  } else {
    if (!f.title.trim()) out.title = 'required';
    if (!f.summary.trim()) out.summary = 'required';
    if (!f.topic) out.topic = 'required';
  }
  return out;
}

/** Whether any check blocks saving a draft (counsel markers only block publishing a final document). */
export function blocksSave(issues: Record<string, ContentIssue[]>, problems: Record<string, string>): boolean {
  return Object.keys(problems).length > 0 || Object.values(issues).some((list) => list.some((i) => i.level === 'error' && i.code !== 'legal-marker'));
}

/** Whether any check blocks publishing. */
export const blocksPublish = (issues: Record<string, ContentIssue[]>, problems: Record<string, string>): boolean =>
  Object.keys(problems).length > 0 || Object.values(issues).some((list) => list.some((i) => i.level === 'error'));

export const ALL_LOCALES: readonly ContentLocale[] = ['en', 'fr', 'es', 'ar', 'yo'];
