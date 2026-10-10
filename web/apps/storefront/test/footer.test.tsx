import { SITE_CONFIG } from '@afrotransact/api-client/mocks';
import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { FooterView } from '@/components/shell/Footer';

import { renderWithProviders } from './render';

describe('footer renders from site config', () => {
  it('shows the configured social links, phone, legal links, locales and payment chips', () => {
    renderWithProviders(<FooterView config={SITE_CONFIG} year={2026} />);
    const socials = screen.getByRole('list', { name: 'Follow AfroTransact' });
    const links = within(socials).getAllByRole('link');
    expect(links).toHaveLength(SITE_CONFIG.socials.length);
    expect(links[0]).toHaveAttribute('href', 'https://www.tiktok.com/@afrotransact');
    expect(links[0]).toHaveAttribute('target', '_blank');
    expect(links[0]).toHaveAttribute('rel', 'noopener noreferrer');
    expect(within(socials).getByRole('link', { name: 'Instagram (opens in a new tab)' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /\+1 \(833\) 555-0199/ })).toHaveAttribute('href', 'tel:+18335550199');
    const legal = screen.getByRole('navigation', { name: 'Legal' });
    expect(within(legal).getByRole('link', { name: 'Privacy Notice' })).toHaveAttribute('href', '/legal/privacy');
    expect(within(screen.getByRole('list', { name: 'Payment methods we accept' })).getAllByRole('listitem')).toHaveLength(7);
    expect(screen.getByRole('option', { name: 'العربية' })).toBeInTheDocument();
    expect(screen.getByText('© 2026 AfroTransact, Inc. All rights reserved.')).toBeInTheDocument();
    for (const col of ['Get to know us', 'Make money with us', 'Let us help you', 'Shop by community']) expect(screen.getByRole('heading', { name: col })).toBeInTheDocument();
  });

  it('changes socials without a deploy: whatever the config returns is rendered', () => {
    const config = { ...SITE_CONFIG, socials: [{ network: 'whatsapp' as const, url: 'https://www.whatsapp.com/channel/abc', label: 'WhatsApp channel' }] };
    renderWithProviders(<FooterView config={config} year={2026} />);
    const links = within(screen.getByRole('list', { name: 'Follow AfroTransact' })).getAllByRole('link');
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAccessibleName('WhatsApp channel (opens in a new tab)');
  });

  it('hides the social list when the config has none', () => {
    renderWithProviders(<FooterView config={{ ...SITE_CONFIG, socials: [] }} year={2026} />);
    expect(screen.queryByRole('list', { name: 'Follow AfroTransact' })).not.toBeInTheDocument();
  });
});
