import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LolaLauncher } from '@/components/assistant/LolaLauncher';
import { ActionBar } from '@/components/listings/form/ActionBar';
import { ReadinessPanel } from '@/components/listings/form/ReadinessPanel';
import { PortalShell } from '@/components/shell/PortalShell';
import { CLEAR_VAR, SupportChat } from '@/components/shell/SupportChat';

import { asRole } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

// The support phone comes from the public site configuration; give it one so the WhatsApp button is shown.
vi.mock('@afrotransact/api-client', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@afrotransact/api-client');
  return { ...actual, createApiClient: () => ({ getSiteConfig: async () => ({ contacts: { phoneE164: '+18335550199' } }) }) };
});

const root = () => document.documentElement.style;

beforeEach(() => {
  resetSellerDb(new Date());
  asRole(null);
  nav.pathname = '/';
  root().removeProperty(CLEAR_VAR);
  root().removeProperty('--at-bottom-bar-h');
});

describe('floating buttons keep clear of the page', () => {
  it('reserves room for the WhatsApp button only while it is shown', async () => {
    const { unmount } = renderWithProviders(<SupportChat orgName="Adire House" />);
    expect(await screen.findByRole('link', { name: /WhatsApp/ })).toBeInTheDocument();
    expect(root().getPropertyValue(CLEAR_VAR)).toBe('5rem');
    unmount();
    expect(root().getPropertyValue(CLEAR_VAR)).toBe('');
  });

  it('pads the bottom of the sidebar and of the icon rail by that room, so their last lines are not under the button', async () => {
    renderWithProviders(<PortalShell><p>page</p></PortalShell>);
    const navs = await screen.findAllByRole('navigation', { name: 'Back office' });
    expect(navs.length).toBeGreaterThanOrEqual(2);
    navs.forEach((n) => expect(n.className).toContain('var(--at-chat-fab-clear,0px)'));
    await waitFor(() => expect(root().getPropertyValue(CLEAR_VAR)).toBe('5rem'));
  });

  it('lets the page end clear of the Ask Lola button', async () => {
    renderWithProviders(<PortalShell><p>page</p></PortalShell>);
    const main = await screen.findByRole('main');
    expect(main.className).toContain('pb-20');
  });

  it('puts Ask Lola above the form’s action bar, which publishes its height and sits flush at the end of the page', async () => {
    const { unmount } = render(
      <ActionBar problems={[]} tips={[]} sections={['basics']} titleOf={() => 'Basics'} percent={100} preview={null} saveState="" primaryLabel="Submit" onPrimary={() => undefined}
        primaryDisabled={false} onSaveDraft={() => undefined} saveDraftDisabled busy={false} savingDraft={false} onGo={() => undefined} />
    );
    expect(screen.getByRole('region', { name: 'Listing actions' }).className).toContain('-mb-20');
    expect(root().getPropertyValue('--at-bottom-bar-h')).toBe('0px');
    unmount();
    expect(root().getPropertyValue('--at-bottom-bar-h')).toBe('');
    cleanup();
    await renderScreen(<LolaLauncher />);
    expect((await screen.findByRole('button', { name: /Ask Lola/ })).className).toContain('var(--at-bottom-bar-h,0px)');
  });

  it('stops the quality panel above Ask Lola: the action bar, the button and a gap come off its height', () => {
    render(<ReadinessPanel problems={[]} tips={[]} sections={['basics']} titleOf={() => 'Basics'} percent={100} preview={null} onGo={() => undefined} />);
    const cls = screen.getByRole('complementary', { name: 'Listing readiness' }).className;
    expect(cls).toContain('var(--at-bottom-bar-h,3.5rem)');
    expect(cls).toContain('-4.5rem)]');
    expect(cls).not.toContain('-9rem');
  });
});
