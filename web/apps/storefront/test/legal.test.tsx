import { createApiClient, LEGAL_DOCUMENT_KEYS } from '@afrotransact/api-client';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { CookieBanner } from '@/components/legal/CookieBanner';
import { headingId, parseLegalMarkdown, renderInline, safeHref } from '@/components/legal/markdown';
import { PrivacyChoices } from '@/components/legal/PrivacyChoices';
import { tEn } from '@/i18n/t';
import {
  ACCEPT_ALL,
  allows,
  browserGpc,
  buildConsent,
  CONSENT_COOKIE,
  consentCookieString,
  effectiveConsent,
  headerGpc,
  parseConsent,
  readConsent,
  REJECT_ALL,
  serializeConsent
} from '@/lib/consent/consent';
import { ConsentProvider } from '@/lib/consent/ConsentProvider';
import { emptyPrivacyRequest, serverErrors, toPrivacyRequest, validatePrivacyRequest } from '@/lib/legal/privacyRequest';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

/* Next's navigation helpers throw control-flow errors; here they throw recognisable ones. */
vi.mock('next/navigation', () => ({
  notFound: () => {
    throw new Error('NEXT_NOT_FOUND');
  },
  permanentRedirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT ${url}`);
  },
  usePathname: () => '/legal/privacy-choices',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() })
}));
vi.mock('@/lib/api/server', () => ({ serverApi: async () => createApiClient({ baseUrl: `${window.location.origin}/api/bff` }) }));
vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

installMockBff();

const clearCookies = () => {
  for (const c of document.cookie.split(';')) {
    const name = c.split('=')[0]?.trim();
    if (name) document.cookie = `${name}=; Path=/; Max-Age=0`;
  }
};
beforeEach(clearCookies);
afterEach(() => {
  Object.defineProperty(navigator, 'globalPrivacyControl', { value: undefined, configurable: true });
});

const withConsent = (ui: ReactElement, gpc = false) =>
  renderWithProviders(
    <ConsentProvider initial={readConsent(document.cookie)} initialGpc={gpc}>
      {ui}
    </ConsentProvider>
  );

describe('consent cookie', () => {
  it('serialises and parses round trip, URL-encoded', () => {
    const s = buildConsent({ functional: true, analytics: false, advertising: true, dns: false }, { gpc: false, version: '2.1', now: new Date('2026-10-11T00:00:00Z'), id: 'rec-1' });
    const raw = serializeConsent(s);
    expect(raw).not.toContain('{');
    expect(parseConsent(raw)).toEqual(s);
    expect(consentCookieString(s, true)).toMatch(/^at_consent=.+; Path=\/; Max-Age=31536000; SameSite=Lax; Secure$/);
    expect(readConsent(`foo=1; ${CONSENT_COOKIE}=${raw}; bar=2`)).toEqual(s);
  });

  it('treats a missing or broken cookie as no choice and reads older formats', () => {
    expect(parseConsent(null)).toBeNull();
    expect(parseConsent('%7Bbroken')).toBeNull();
    expect(parseConsent(encodeURIComponent('[1]'))).toBeNull();
    const legacy = parseConsent(encodeURIComponent(JSON.stringify({ accepted: true })));
    expect(legacy).toMatchObject({ functional: true, analytics: true, advertising: true, dns: false });
    expect(parseConsent(encodeURIComponent(JSON.stringify({ marketing: true })))?.advertising).toBe(true);
  });

  it('honours Global Privacy Control and "Do not sell or share"', () => {
    const gpc = buildConsent(ACCEPT_ALL, { gpc: true, version: '2.1' });
    expect(gpc).toMatchObject({ advertising: false, dns: true, gpc: true, analytics: true });
    const dns = buildConsent({ ...ACCEPT_ALL, dns: true }, { gpc: false, version: '2.1' });
    expect(dns.advertising).toBe(false);
    const stored = buildConsent(ACCEPT_ALL, { gpc: false, version: '2.1' });
    expect(effectiveConsent(stored, true)).toMatchObject({ advertising: false, dns: true });
    expect(effectiveConsent(stored, false)).toBe(stored);
    expect(allows(null, 'analytics')).toBe(false);
    expect(allows(null, 'necessary')).toBe(true);
    expect(allows(buildConsent(REJECT_ALL, { gpc: false, version: '' }), 'functional')).toBe(false);
    expect(headerGpc('1')).toBe(true);
    expect(headerGpc('0')).toBe(false);
    expect(headerGpc(null)).toBe(false);
    expect(browserGpc({ globalPrivacyControl: true })).toBe(true);
    expect(browserGpc({ globalPrivacyControl: 'true' })).toBe(false);
    expect(browserGpc(undefined)).toBe(false);
  });
});

describe('privacy request validation', () => {
  it('lists every problem with the design wording keys', () => {
    expect(validatePrivacyRequest(emptyPrivacyRequest())).toEqual({ type: 'legal.errType', email: 'legal.errEmail', state: 'legal.errState' });
    expect(validatePrivacyRequest({ type: 'delete', email: 'ada@example', state: 'TX', details: '' })).toEqual({ email: 'legal.errEmail' });
    expect(validatePrivacyRequest({ type: 'delete', email: 'ada@example.com', state: 'ZZ', details: 'x'.repeat(2001) })).toEqual({ state: 'legal.errState', details: 'legal.errDetails' });
    const ok = { type: 'optout', email: ' ada@example.com ', state: 'DC', details: '  ' } as const;
    expect(validatePrivacyRequest(ok)).toEqual({});
    expect(toPrivacyRequest(ok, 'fr')).toEqual({ type: 'optout', email: 'ada@example.com', state: 'DC', locale: 'fr' });
    expect(serverErrors({ email: 'Enter a valid email', other: 'x' })).toEqual({ email: 'legal.errEmail' });
  });
});

describe('legal markdown', () => {
  it('splits sections, keeps outline ids, renders lists and only safe links', () => {
    const doc = parseLegalMarkdown('# Title\n\nIntro text.\n\n## How we use it\n\nLine one\nline two.\n\n- a\n- b **bold**\n\n## Contact\n\nEmail privacy@afrotransact.com or [see](javascript:alert(1)).\n', [
      { id: 'how-we-use-it', title: 'How we use it' },
      { id: 'contact-us', title: 'Contact' }
    ]);
    expect(doc.intro).toEqual([{ kind: 'p', text: 'Intro text.' }]);
    expect(doc.sections.map((s) => s.id)).toEqual(['how-we-use-it', 'contact-us']);
    expect(doc.sections[0]?.blocks).toEqual([{ kind: 'p', text: 'Line one line two.' }, { kind: 'ul', items: ['a', 'b **bold**'] }]);
    expect(headingId('Restricted — approval needed')).toBe('restricted-approval-needed');
    expect(safeHref('javascript:alert(1)')).toBeNull();
    expect(safeHref('//evil.example')).toBeNull();
    expect(safeHref('/legal/privacy')).toBe('/legal/privacy');
    const nodes = renderInline('Write to legal@afrotransact.com.');
    expect(nodes).toHaveLength(3);
  });
});

describe('/legal/[doc] routing', () => {
  const run = async (doc: string, version?: string) => {
    const { default: Page } = await import('@/app/(shop)/legal/[doc]/page');
    return Page({ params: Promise.resolve({ doc }), searchParams: Promise.resolve(version ? { version } : {}) });
  };

  it('404s unknown documents and versions, and redirects legacy slugs', async () => {
    await expect(run('cookie-monster')).rejects.toThrow('NEXT_NOT_FOUND');
    await expect(run('terms')).rejects.toThrow('NEXT_REDIRECT /legal/conditions-of-use');
    await expect(run('sms', '1.0')).rejects.toThrow('NEXT_REDIRECT /legal/sms-terms?version=1.0');
    await expect(run('privacy', '0.9')).rejects.toThrow('NEXT_NOT_FOUND');
  });

  it('renders every document with its title, version and "On this page"', async () => {
    for (const key of LEGAL_DOCUMENT_KEYS) {
      const el = await run(key);
      const view = withConsent(el);
      expect(screen.getByRole('heading', { level: 1, name: tEn(`legal.doc.${key}`) })).toBeInTheDocument();
      expect(screen.getByRole('navigation', { name: 'On this page' })).toBeInTheDocument();
      expect(screen.getAllByText(/^Version \d/)[0]).toBeInTheDocument();
      expect(screen.getByRole('link', { name: tEn(`legal.doc.${key}`) })).toHaveAttribute('aria-current', 'page');
      view.unmount();
    }
  });

  it('shows an archived version with a link back to the current one', async () => {
    withConsent(await run('conditions-of-use', '3.1'));
    expect(screen.getByText(/You are reading version 3.1/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Read the current version' })).toHaveAttribute('href', '/legal/conditions-of-use');
  });
});

describe('cookie banner', () => {
  it('stays until a choice is made, then writes the consent cookie', async () => {
    withConsent(<CookieBanner />);
    const banner = screen.getByRole('region', { name: 'Your cookie choices' });
    expect(within(banner).getByRole('link', { name: 'Manage preferences' })).toHaveAttribute('href', '/legal/cookies');
    await userEvent.click(within(banner).getByRole('button', { name: 'Reject non-essential' }));
    expect(screen.queryByRole('region', { name: 'Your cookie choices' })).not.toBeInTheDocument();
    await waitFor(() => expect(readConsent(document.cookie)?.id).toBeTruthy());
    expect(readConsent(document.cookie)).toMatchObject({ functional: false, analytics: false, advertising: false });
  });

  it('mentions Global Privacy Control and never stores advertising consent under it', async () => {
    Object.defineProperty(navigator, 'globalPrivacyControl', { value: true, configurable: true });
    withConsent(<CookieBanner />);
    expect(screen.getByText(/Global Privacy Control signal/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Accept all' }));
    expect(readConsent(document.cookie)).toMatchObject({ analytics: true, advertising: false, dns: true, gpc: true });
  });
});

describe('Your Privacy Choices', () => {
  it('validates, then submits a request and shows the reference', async () => {
    withConsent(<PrivacyChoices privacyEmail="privacy@afrotransact.com" />);
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    const summary = await screen.findByRole('alert', { name: 'There are 3 problems with your request' });
    expect(within(summary).getByRole('link', { name: 'Choose your state' })).toHaveAttribute('href', '#f-state');

    await userEvent.click(screen.getByRole('radio', { name: 'Delete my data' }));
    await userEvent.type(screen.getByLabelText('Email on your account'), 'ada@example.com');
    await userEvent.selectOptions(screen.getByLabelText('State of residence'), 'TX');
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }));
    expect(await screen.findByText(/Request received\. Reference PR-\d{6}\. Check ada@example\.com/)).toBeInTheDocument();
  });

  it('prefills a signed-in shopper’s email and stores "Do not sell or share" with the consent', async () => {
    await signInDemo();
    withConsent(<PrivacyChoices privacyEmail="privacy@afrotransact.com" />);
    await waitFor(() => expect((screen.getByLabelText('Email on your account') as HTMLInputElement).value).toMatch(/@/));
    const sw = screen.getByRole('switch', { name: 'Do not sell or share my personal information' });
    expect(sw).toHaveAttribute('aria-checked', 'false');
    await act(async () => {
      await userEvent.click(sw);
    });
    expect(sw).toHaveAttribute('aria-checked', 'true');
    expect(readConsent(document.cookie)).toMatchObject({ dns: true, advertising: false });
  });
});
