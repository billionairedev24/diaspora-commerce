import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Header } from '@/components/shell/Header';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

describe('storefront header (fixed while the page scrolls)', () => {
  it('keeps the top bar and the department bar in the sticky header; the announcement bar scrolls away under it', async () => {
    const { unmount } = renderWithProviders(<Header />);
    const header = screen.getByTestId('site-header');
    expect(header).toHaveClass('header');
    expect(header).toContainElement(screen.getByRole('navigation', { name: 'Departments' }));
    expect(header).toContainElement(screen.getByRole('link', { name: /Cart, \d+ items/ }));
    const announcement = await screen.findByRole('region', { name: 'Announcements' });
    expect(header).not.toContainElement(announcement);
    // The live header height feeds scroll-padding-top so keyboard focus never lands under it (WCAG 2.4.11).
    expect(document.documentElement.style.getPropertyValue('--at-sticky-header-h')).toMatch(/^\d+px$/);
    unmount();
    expect(document.documentElement.style.getPropertyValue('--at-sticky-header-h')).toBe('');
  });
});
