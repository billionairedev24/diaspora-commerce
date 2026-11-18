import { type AttributeDef } from '@afrotransact/api-client';
import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandField, type BrandValue } from '@/components/listings/form/BrandField';

import { renderScreen } from './render';

/* The brand picker: the category's rule decides required, brand-or-reason or optional; it searches the department's
   brands, says "did you mean" for a close spelling, always offers to add the typed name, and never blocks the seller. */

const DEF = (over: Partial<AttributeDef>): AttributeDef => ({
  key: 'brand', label: 'Brand', options: [], required: false, multiline: false, kind: 'BRAND', units: [], section: 'DETAILS', help: null, taxFact: false, evidence: false, optionLabels: {}, ...over
});
const NAMED = DEF({ required: true, options: [] });
const EXEMPT = DEF({ required: true, options: ['UNBRANDED', 'HANDMADE'] });
const OPTIONAL = DEF({ required: false, options: ['UNBRANDED', 'HANDMADE'] });
const EMPTY: BrandValue = { brand: '', brand_id: '', brand_reason: '' };

const changes = vi.fn<(v: BrandValue) => void>();

function Harness({ def, gated = false, category = 'pt-dresses' }: { def: AttributeDef; gated?: boolean; category?: string }) {
  const [value, setValue] = useState<BrandValue>(EMPTY);
  return (
    <BrandField id="pf-brand" def={def} categoryId={category} productType="dress" gated={gated} value={value}
      onChange={(v) => {
        changes(v);
        setValue(v);
      }} />
  );
}

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  changes.mockReset();
});

const open = async (def: AttributeDef, props: { gated?: boolean; category?: string } = {}) => {
  const user = userEvent.setup();
  await renderScreen(<Harness def={def} {...props} />);
  const box = await screen.findByRole('combobox', { name: /Brand/ });
  await user.click(box);
  await screen.findByRole('option', { name: 'Nike' });
  return { user, box };
};

describe('required, brand-or-reason and optional', () => {
  it('a named brand is required and offers no way out; a not-listed brand is added instead', async () => {
    const { box } = await open(NAMED);
    expect(box).toBeRequired();
    expect(screen.queryByRole('option', { name: 'No brand' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Handmade (no brand)' })).not.toBeInTheDocument();
    expect(box).toHaveAttribute('placeholder', 'Search or add a brand');
  });

  it('a brand-or-reason field is required and offers No brand and Handmade after the brands', async () => {
    const { box } = await open(EXEMPT);
    expect(box).toBeRequired();
    const names = screen.getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual(expect.arrayContaining(['No brand', 'Handmade (no brand)']));
    expect(names.indexOf('Nike')).toBeLessThan(names.indexOf('No brand'));
  });

  it('an optional brand is not marked required, lists No brand first and can be left empty', async () => {
    const { user, box } = await open(OPTIONAL);
    expect(box).not.toBeRequired();
    expect(screen.queryByText('required')).not.toBeInTheDocument();
    expect(box).toHaveAttribute('placeholder', 'Optional: search, add or choose No brand');
    const names = screen.getAllByRole('option').map((o) => o.textContent);
    expect(names.indexOf('No brand')).toBeLessThan(names.indexOf('Nike'));
    await user.click(screen.getByRole('option', { name: 'No brand' }));
    expect(changes).toHaveBeenLastCalledWith({ brand: '', brand_id: '', brand_reason: 'UNBRANDED' });
    expect(box).toHaveValue('No brand');
  });

  it('says in the tooltip why a required brand is needed, and in a note when some brands are gated', async () => {
    const user = userEvent.setup();
    await renderScreen(<Harness def={NAMED} gated />);
    await screen.findByRole('combobox', { name: /Brand/ });
    expect(screen.getByText(/Some brands in this category need AfroTransact approval before listings go live. A new brand, or an item with no brand, does not./)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /info|more/i }));
    expect(await screen.findByText(/needs a named brand so shoppers and our team can trace the product/)).toBeInTheDocument();
  });

  it('never uses the word offer', async () => {
    await open(EXEMPT, { gated: true });
    expect(document.body.textContent ?? '').not.toMatch(/\boffer/i);
  });
});

describe('typing a brand that is not listed', () => {
  it('always offers Add “…” as a new brand, keeps the seller going and shows the review note', async () => {
    const { user, box } = await open(NAMED);
    await user.type(box, 'Zuri Naturals');
    await user.click(await screen.findByRole('option', { name: 'Add “Zuri Naturals” as a new brand' }));
    await waitFor(() => expect(box).toHaveValue('Zuri Naturals'));
    const made = changes.mock.calls.at(-1)?.[0];
    expect(made?.brand).toBe('Zuri Naturals');
    expect(made?.brand_id).not.toBe('');
    expect(getSellerDb().pendingBrands.map((b) => b.name)).toContain('Zuri Naturals');
    expect(await screen.findByText(/Added for AfroTransact to review. You can keep listing/)).toBeInTheDocument();
  });

  it('says did you mean for a close spelling and chooses the listed brand instead of making a duplicate', async () => {
    const { user, box } = await open(NAMED);
    await user.type(box, 'Nikee');
    const near = await screen.findByRole('option', { name: /Nike\s*Did you mean this brand\?/ });
    expect(screen.getByRole('option', { name: 'Add “Nikee” as a new brand' })).toBeInTheDocument();
    await user.click(near);
    expect(changes).toHaveBeenLastCalledWith(expect.objectContaining({ brand: 'Nike', brand_reason: '' }));
    expect(getSellerDb().pendingBrands.map((b) => b.name)).not.toContain('Nikee');
  });

  it('points at the existing brand when the typed name differs only in case, accents or punctuation', async () => {
    const { user, box } = await open(NAMED);
    await user.type(box, 'Levis');
    expect(await screen.findByRole('option', { name: /Levi’s\s*Already listed, use this one/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Add “Levis” as a new brand' })).toBeInTheDocument();
  });

  it('adding a name that is already listed returns the listed brand, never a second one', async () => {
    const { user, box } = await open(NAMED);
    await user.type(box, 'LEVIS');
    await user.click(await screen.findByRole('option', { name: 'Add “LEVIS” as a new brand' }));
    await waitFor(() => expect(box).toHaveValue('Levi’s'));
    expect(getSellerDb().pendingBrands.map((b) => b.name)).not.toContain('LEVIS');
  });

  it('explains a name that cannot be a brand (an e-mail address) and adds nothing', async () => {
    const { user, box } = await open(NAMED);
    await user.type(box, 'me@shop.com');
    expect(await screen.findByText('This name cannot be added as a brand: No e-mail addresses')).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: 'Add “me@shop.com” as a new brand' }));
    expect(await screen.findByText('No e-mail addresses')).toBeInTheDocument();
    expect(getSellerDb().pendingBrands.map((b) => b.name)).not.toContain('me@shop.com');
  });
});
