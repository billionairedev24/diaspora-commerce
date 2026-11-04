import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  countLegalMarkers,
  errorsOf,
  fillPlaceholders,
  HelpPageContent,
  LegalDocumentContent,
  LegalDraftBanner,
  outlineOf,
  parseLegalMarkdown,
  placeholdersUsed,
  slugOf,
  validateMarkdown
} from '../src';

const DOC = '# Privacy\n\nIntro for {legal}.\n\n## Data we keep\n\nWe keep it. [[LEGAL: Q4 retention]]\n\n## Your choices\n\n- Ask us at {phone}\n- See [data](#data-we-keep)';

describe('placeholders and outline (same rules as content-service)', () => {
  it('fills known tokens and leaves unknown ones', () => {
    expect(fillPlaceholders('Write to {legal} or {nobody}', { legal: 'legal@afrotransact.com' })).toBe('Write to legal@afrotransact.com or {nobody}');
    expect(placeholdersUsed('{phone} {legal} {phone}')).toEqual(['phone', 'legal']);
  });

  it('builds the same anchors as the server: accents folded, duplicates numbered', () => {
    expect(slugOf('Opting out & getting help')).toBe('opting-out-getting-help');
    expect(slugOf('Données personnelles')).toBe('donnees-personnelles');
    expect(slugOf('عربى')).toBe('section');
    expect(outlineOf('## A\n\n## A\n\n### skipped\n\n## B c')).toEqual([
      { id: 'a', title: 'A' },
      { id: 'a-2', title: 'A' },
      { id: 'b-c', title: 'B c' }
    ]);
  });
});

describe('validateMarkdown', () => {
  const legal = { kind: 'legal' as const, final: false };

  it('flags repeated section names, links that go nowhere and anchors with no section', () => {
    const issues = validateMarkdown('## One\n\n## one\n\n[x]()\n\n[y](javascript:alert(1))\n\n[z](#nowhere)\n\n[ok](#one) [ok](/orders) [ok](https://a.test) [ok](mailto:a@b.test)', legal);
    expect(issues.map((i) => i.code)).toEqual(['duplicate-heading', 'broken-link', 'broken-link', 'broken-anchor']);
    expect(issues[0]).toMatchObject({ subject: 'one', line: 3 });
  });

  it('treats counsel markers as a warning in a draft and an error when publishing as final', () => {
    expect(validateMarkdown(DOC, legal).find((i) => i.code === 'legal-marker')).toMatchObject({ level: 'warning', count: 1 });
    expect(errorsOf(validateMarkdown(DOC, { ...legal, final: true })).map((i) => i.code)).toEqual(['legal-marker']);
    expect(countLegalMarkers('[[LEGAL: a]] and [[LEGAL: b]]')).toBe(2);
  });

  it('flags tokens the site contacts cannot fill', () => {
    expect(validateMarkdown('## A\n\nCall {phone} or {fax}', { ...legal, placeholders: { phone: '1' } }).map((i) => i.subject)).toEqual(['{fax}']);
  });

  it('requires a heading before any text on a help page, and some text at all', () => {
    expect(validateMarkdown('Hello\n\n## A\n\nx', { kind: 'help', final: false }).map((i) => i.code)).toEqual(['text-before-heading']);
    expect(validateMarkdown('  ', { kind: 'help', final: false }).map((i) => i.code)).toEqual(['empty']);
    expect(validateMarkdown('## \n\nx', legal).map((i) => i.code)).toContain('empty-heading');
  });
});

describe('LegalDocumentContent', () => {
  const props = {
    title: 'Privacy Notice',
    body: fillPlaceholders(DOC, { legal: 'legal@afrotransact.com', phone: '+1 833 555 0199' }),
    versionLabel: 'Version 3.2',
    effectiveLabel: 'Effective',
    effectiveDate: '2026-09-01',
    effectiveText: '1 September 2026',
    tocLabel: 'On this page'
  };

  it('renders the title, version, effective date, contents and numbered sections', () => {
    render(<LegalDocumentContent {...props} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Privacy Notice' })).toBeInTheDocument();
    expect(screen.getByText('Version 3.2')).toBeInTheDocument();
    expect(screen.getByText('1 September 2026')).toHaveAttribute('datetime', '2026-09-01');
    const toc = screen.getByRole('navigation', { name: 'On this page' });
    expect(within(toc).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['#data-we-keep', '#your-choices']);
    expect(screen.getByRole('heading', { level: 2, name: '1. Data we keep' })).toHaveAttribute('id', 'data-we-keep');
    expect(screen.getByText(/Ask us at \+1 833 555 0199/)).toBeInTheDocument();
  });

  it('leaves counsel markers as plain text for shoppers and highlights them in the staff preview', () => {
    const { container, rerender } = render(<LegalDocumentContent {...props} />);
    expect(container.querySelector('[data-legal-marker]')).toBeNull();
    rerender(<LegalDocumentContent {...props} highlightMarkers />);
    const marks = container.querySelectorAll('mark[data-legal-marker]');
    expect(marks).toHaveLength(1);
    expect(marks[0]).toHaveTextContent('[[LEGAL: Q4 retention]]');
  });

  it('shows notes, extra meta, and the interactive part', () => {
    render(
      <LegalDocumentContent {...props} notes={<p>Translation note</p>} meta={<button type="button">Print</button>}>
        <p>Cookie preferences</p>
      </LegalDocumentContent>
    );
    expect(screen.getByText('Translation note')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Print' })).toBeInTheDocument();
    expect(screen.getByText('Cookie preferences')).toBeInTheDocument();
  });

  it('marks the body language', () => {
    const { container } = render(<LegalDocumentContent {...props} bodyLang="ar" bodyDir="rtl" />);
    expect(container.querySelector('article')).toHaveAttribute('lang', 'ar');
    expect(container.querySelector('article')).toHaveAttribute('dir', 'rtl');
  });

  it('renders the draft banner as a note', () => {
    render(<LegalDraftBanner>Draft for legal counsel review</LegalDraftBanner>);
    expect(screen.getByRole('note')).toHaveTextContent('Draft for legal counsel review');
  });
});

describe('HelpPageContent', () => {
  it('shows the title, a bold heading over each section and the breadcrumb', () => {
    render(
      <HelpPageContent title="Shipping" crumb={<nav aria-label="crumb">Help center</nav>} sections={[{ heading: 'Pickup', body: 'Collect at a **locker** or [orders](/orders).' }]} />
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Shipping' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Pickup' })).toBeInTheDocument();
    expect(screen.getByText('locker').tagName).toBe('STRONG');
    expect(screen.getByRole('link', { name: 'orders' })).toHaveAttribute('href', '/orders');
    expect(screen.getByRole('navigation', { name: 'crumb' })).toBeInTheDocument();
  });

  it('parses a document with the same sections the page renders', () => {
    expect(parseLegalMarkdown(DOC).sections.map((s) => s.title)).toEqual(['Data we keep', 'Your choices']);
  });
});
