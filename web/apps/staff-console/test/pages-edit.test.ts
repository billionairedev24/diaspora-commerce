import { describe, expect, it } from 'vitest';

import { PREVIEW_CHROME } from '@/components/pages/chrome';
import { insertLink, insertSectionLink, insertTable, offsetOfLine, prefixLines, toggleHeading, wrap } from '@/components/pages/edit';
import { blocksPublish, blocksSave, checkForm, type EditorForm, fieldProblems, initialForm, isDirty, nextMinor, toInput } from '@/components/pages/form';
import { hasStructure, htmlToMarkdown } from '@/components/pages/paste';

import ar from '../../storefront/src/i18n/ar.json';
import en from '../../storefront/src/i18n/en.json';
import es from '../../storefront/src/i18n/es.json';
import fr from '../../storefront/src/i18n/fr.json';
import yo from '../../storefront/src/i18n/yo.json';

describe('toolbar commands', () => {
  it('toggles a heading on the current line', () => {
    expect(toggleHeading({ text: 'Fees\nOther', start: 2, end: 2 }, 2)).toMatchObject({ text: '## Fees\nOther' });
    expect(toggleHeading({ text: '## Fees', start: 4, end: 4 }, 2).text).toBe('Fees');
    expect(toggleHeading({ text: '## Fees', start: 4, end: 4 }, 3).text).toBe('### Fees');
  });

  it('wraps the selection in bold, unwraps it, and offers a sample when nothing is selected', () => {
    expect(wrap({ text: 'a word b', start: 2, end: 6 }, '**', 'bold text')).toEqual({ text: 'a **word** b', start: 4, end: 8 });
    expect(wrap({ text: 'a **word** b', start: 4, end: 8 }, '**', 'bold text').text).toBe('a word b');
    const sample = wrap({ text: '', start: 0, end: 0 }, '*', 'italic text');
    expect(sample).toEqual({ text: '*italic text*', start: 1, end: 12 });
  });

  it('makes bullets, numbers and quotes of the selected lines, and takes them off again', () => {
    expect(prefixLines({ text: 'one\ntwo\nthree', start: 0, end: 7 }, 'ul').text).toBe('- one\n- two\nthree');
    expect(prefixLines({ text: 'one\ntwo', start: 0, end: 7 }, 'ol').text).toBe('1. one\n2. two');
    expect(prefixLines({ text: '- one\n- two', start: 0, end: 11 }, 'ul').text).toBe('one\ntwo');
    expect(prefixLines({ text: 'said it', start: 0, end: 0 }, 'quote').text).toBe('> said it');
  });

  it('inserts a link with the address selected, a table, and a link to a section', () => {
    expect(insertLink({ text: 'see terms', start: 4, end: 9 })).toEqual({ text: 'see [terms](https://)', start: 12, end: 20 });
    expect(insertTable({ text: 'Text', start: 4, end: 4 }).text).toBe('Text\n\n| Column | Column |\n| --- | --- |\n| Text | Text |\n');
    expect(insertSectionLink({ text: '', start: 0, end: 0 }, { id: 'your-choices', title: 'Your choices' }).text).toBe('[Your choices](#your-choices)');
    expect(offsetOfLine('a\nbb\nccc', 3)).toBe(5);
  });
});

describe('paste from a word processor or the web', () => {
  it('keeps structure: headings become sections, runs become bold and italic, lists and links survive', () => {
    const html = '<h1>Returns</h1><p>Most items <b>can</b> be <i>returned</i> in <a href="/account/returns">30 days</a>.</p><ul><li>Photos help</li><li>Keep packaging<ul><li>Food too</li></ul></li></ul><h3>Refunds</h3><ol><li>Request</li><li>Wait</li></ol>';
    expect(hasStructure(html)).toBe(true);
    expect(htmlToMarkdown(html)).toBe('## Returns\n\nMost items **can** be *returned* in [30 days](/account/returns).\n\n- Photos help\n- Keep packaging\n  - Food too\n\n### Refunds\n\n1. Request\n2. Wait');
  });

  it('converts tables, drops unsafe links and styles, and ignores a plain styled run', () => {
    expect(htmlToMarkdown('<style>p{color:red}</style><table><tr><th>Fee</th><th>Rate</th></tr><tr><td>Card</td><td>2.9%</td></tr></table>')).toBe('| Fee | Rate |\n| --- | --- |\n| Card | 2.9% |');
    expect(htmlToMarkdown('<p><a href="javascript:alert(1)">click</a></p>')).toBe('click');
    expect(hasStructure('<span style="font-size:12px">just text</span>')).toBe(false);
  });
});

const page = (draft = false) => ({
  kind: 'legal' as const,
  key: 'privacy',
  title: 'Privacy Notice',
  group: 'Privacy',
  livePath: '/legal/privacy',
  liveUrl: 'https://shop.test/legal/privacy',
  locales: ['en', 'fr', 'es', 'ar', 'yo'] as ('en' | 'fr' | 'es' | 'ar' | 'yo')[],
  published: { version: '4.0', effectiveDate: '2026-09-01', draftBanner: true, title: 'Privacy Notice', summary: null, topic: null, sortOrder: null, bodies: { en: '# P\n\n## A\n\nx' }, needsReview: [], changeSummary: null, rowVersion: null, updatedAt: null, updatedByName: null, reason: null },
  draft: draft ? { version: '4.2', effectiveDate: '2026-10-01', draftBanner: false, title: 'Privacy Notice', summary: null, topic: null, sortOrder: null, bodies: { en: '# P\n\n## A\n\ny' }, needsReview: ['fr'], changeSummary: 'Edit', rowVersion: 3, updatedAt: null, updatedByName: 'Ada', reason: null } : null,
  versions: [],
  placeholders: { phone: '1' },
  topics: [],
  access: { canEdit: true, canPublish: true, secondApprover: true, reason: null }
});

describe('the editor form', () => {
  it('starts from the published page with the next version, or from the saved draft', () => {
    expect(nextMinor('4.0')).toBe('4.1');
    expect(initialForm(page())).toMatchObject({ version: '4.1', draftBanner: true, changeSummary: '' });
    expect(initialForm(page(true))).toMatchObject({ version: '4.2', draftBanner: false, needsReview: ['fr'], changeSummary: 'Edit' });
  });

  it('knows a form with no change from one with a change (key order and review order do not count)', () => {
    const base = initialForm(page(true));
    expect(isDirty({ ...base, bodies: { ...base.bodies }, needsReview: [...base.needsReview] }, base)).toBe(false);
    expect(isDirty({ ...base, changeSummary: 'Edit!' }, base)).toBe(true);
    expect(isDirty({ ...base, bodies: { ...base.bodies, fr: 'Bonjour' } }, base)).toBe(true);
  });

  it('builds the save request with the draft version it loaded, only legal fields for legal', () => {
    const f = initialForm(page(true));
    expect(toInput('legal', f, 3)).toEqual({ bodies: f.bodies, needsReview: [], changeSummary: 'Edit', rowVersion: 3, version: '4.2', effectiveDate: '2026-10-01', draftBanner: false });
    expect(toInput('legal', { ...f, needsReview: ['fr'] }, null)).not.toHaveProperty('rowVersion');
  });

  it('blocks saving on real problems but not on counsel markers, and blocks publishing on markers in a final document', () => {
    const f: EditorForm = { ...initialForm(page()), draftBanner: false, version: '4.1', effectiveDate: '2026-11-01', bodies: { en: '## A\n\nOpen [[LEGAL: Q4]]' } };
    const issues = checkForm('legal', f, { phone: '1' });
    expect(blocksSave(issues, fieldProblems('legal', f))).toBe(false);
    expect(blocksPublish(issues, fieldProblems('legal', f))).toBe(true);
    const banner = checkForm('legal', { ...f, draftBanner: true }, { phone: '1' });
    expect(blocksPublish(banner, {})).toBe(false);
    const dup = checkForm('legal', { ...f, bodies: { en: '## A\n\nx\n\n## a\n\ny' } }, {});
    expect(blocksSave(dup, {})).toBe(true);
    expect(fieldProblems('legal', { ...f, version: 'v4' })).toEqual({ version: 'version' });
    expect(fieldProblems('help', { ...f, title: '', summary: 'x', topic: 'SELLING' })).toEqual({ title: 'required' });
  });
});

describe('the words around a page in each language match the storefront catalogues', () => {
  const storefront = { en, fr, es, ar, yo } as unknown as Record<string, Record<string, string>>;
  it.each(['en', 'fr', 'es', 'ar', 'yo'] as const)('%s', (locale) => {
    const c = storefront[locale] as Record<string, string>;
    expect(PREVIEW_CHROME[locale]).toEqual({
      version: c['legal.version'],
      effective: c['legal.effective'],
      onPage: c['legal.onPage'],
      translation: c['legal.translation'],
      draft: c['legal.draft'],
      helpCrumb: c['help.hubCrumb'],
      helpCrumbAria: c['help.crumbAria']
    });
  });
});
