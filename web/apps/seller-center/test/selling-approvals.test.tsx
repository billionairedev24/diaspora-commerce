import { decideSellingMock, resetSellerDb, resetSellingMocks, SELLER_ORG } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { SellingApplyChip } from '@/components/selling/SellingApplyChip';
import { SellingApprovalsView } from '@/components/selling/SellingApprovalsView';
import { useSellingStatus } from '@/lib/selling-status';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  resetSellingMocks();
  asRole(null);
  nav.pathname = '/listings/approvals';
});

function Chip({ category, brand, material }: { category: string; brand: string; material?: string }) {
  const selling = useSellingStatus(category, brand, { material });
  return (
    <div>
      <SellingApplyChip selling={selling} />
      <span data-testid="level">{selling.status}</span>
      <span data-testid="pending">{selling.pendingFacts.join(',')}</span>
      <span data-testid="href">{selling.applyHref ?? ''}</span>
    </div>
  );
}

describe('the product form chip (useSellingStatus)', () => {
  it('says "Sell this product" for an open category', async () => {
    await renderScreen(<Chip category="packaged-snacks" brand="Tasty" />, SELLER_ORG);
    expect(await screen.findByText('Sell this product')).toBeInTheDocument();
    expect(screen.getByTestId('level')).toHaveTextContent('SELL');
  });

  it('says "Apply to sell" with a button that opens the application for the scope in the way', async () => {
    await renderScreen(<Chip category="dietary-supplements" brand="" />, SELLER_ORG);
    expect(await screen.findByRole('link', { name: 'Apply to sell' })).toHaveAttribute('href', '/listings/approvals?apply=CATEGORY%3Adietary-supplements');
    expect(screen.getByText(/You need approval to sell in Dietary supplements/)).toBeInTheDocument();
    expect(screen.getByTestId('level')).toHaveTextContent('APPLY');
  });

  it('says "Not available" with the reason and no button', async () => {
    await renderScreen(<Chip category="gift-baskets-bundles" brand="" />, SELLER_ORG);
    expect(await screen.findByText('Not available')).toBeInTheDocument();
    expect(screen.getByText(/bundles can be itemised/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Apply to sell' })).not.toBeInTheDocument();
  });

  it('asks for the material for jewelry', async () => {
    await renderScreen(<Chip category="jewelry-accessories" brand="" />, SELLER_ORG);
    await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('material'));
    expect(screen.getByTestId('level')).toHaveTextContent('SELL');
  });

  it('needs approval for jewelry made of gold', async () => {
    await renderScreen(<Chip category="jewelry-accessories" brand="" material="gold" />, SELLER_ORG);
    await waitFor(() => expect(screen.getByTestId('level')).toHaveTextContent('APPLY'));
  });
});

describe('Selling approvals page', () => {
  it('lists every scope with its status in plain words and applies with documents', async () => {
    await renderScreen(<SellingApprovalsView />, SELLER_ORG);
    expect(await screen.findByText('Dietary supplements')).toBeInTheDocument();
    expect(screen.getByText('Gift baskets / bundles')).toBeInTheDocument();
    expect(screen.getAllByText('Not applied').length).toBeGreaterThan(0);
    fireEvent.click(screen.getAllByRole('button', { name: 'Apply to sell' })[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog', { name: /Apply to sell:/ });
    expect(dialog).toHaveTextContent(/Your files are private/);
    expect(screen.getByRole('button', { name: 'Apply to sell' })).toBeDisabled();
  });

  it('shows an approved scope with when it runs until, and a warning that renewal is due', async () => {
    const api = mockApi();
    await api.setActiveOrg(SELLER_ORG);
    const future = new Date(Date.now() + 200 * 86_400_000).toISOString().slice(0, 10);
    const filed = await api.applyToSell({ scopeType: 'CATEGORY', scopeKey: 'dietary-supplements', documents: [
      { kind: 'PERMIT_LICENCE', assetId: 'p', expiresOn: future }, { kind: 'SAFETY_CERTIFICATE', assetId: 's' }, { kind: 'LIABILITY_INSURANCE', assetId: 'i', expiresOn: future, namesAfroTransact: true }] });
    decideSellingMock(SELLER_ORG, filed.id, 'APPROVED', 20);
    await renderScreen(<SellingApprovalsView />, SELLER_ORG);
    expect(await screen.findByText('Approved')).toBeInTheDocument();
    expect(screen.getByText(/runs out in 2\d days/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Renew' }).length).toBeGreaterThan(0);
  });
});
