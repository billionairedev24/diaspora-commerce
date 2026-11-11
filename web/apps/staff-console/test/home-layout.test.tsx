import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { MarketingView } from '@/components/marketing/MarketingView';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

/* Home page layout: the home body in page order, with a preview and disclosure per module and move up / down without a
   new review. New paid slots and home modules are choices of the placement form. */

beforeEach(() => {
  resetStaffDb(new Date());
  asRoles(null);
});

const q = { status: '', q: '', page: 0, size: 25, sort: [] };
const order = async () => (await mockApi().listResource('home-layout', q)).rows.map((r) => r.id);

describe('home page layout', () => {
  it('lists the modules in page order, highest priority first, each with its disclosure', async () => {
    const page = await mockApi().listResource('home-layout', q);
    expect(page.rows[0]?.id).toBe('plc-b1');
    expect(page.rows.map((r) => r.id)).toEqual(['plc-b1', 'plc-b2', 'plc-b3', 'plc-b4', 'plc-b5', 'plc-b6']);
    const brand = await mockApi().getRecord('home-layout', 'plc-b2');
    expect(brand.subtitle).toContain('Labelled Sponsored · Kingston Kitchen');
    const organic = await mockApi().getRecord('home-layout', 'plc-b1');
    expect(organic.subtitle).toContain('Organic (no label)');
    expect(JSON.stringify(brand.sections)).toContain('Preview');
  });

  it('moves a module up or down without a new review, and sets an exact priority', async () => {
    await mockApi().runAction('home-layout', 'plc-b3', 'move-up', {});
    expect((await order()).slice(0, 3)).toEqual(['plc-b1', 'plc-b3', 'plc-b2']); // 70 -> 85: past the banner at 80
    await mockApi().runAction('home-layout', 'plc-b3', 'move-up', {});
    expect((await order()).slice(0, 3)).toEqual(['plc-b3', 'plc-b1', 'plc-b2']);
    await mockApi().runAction('home-layout', 'plc-b6', 'set-priority', { priority: 105 });
    expect((await order())[0]).toBe('plc-b6');
  });

  it('is a tab of Marketing and offers the new slots when creating a placement', async () => {
    const page = await mockApi().listResource('placements', q);
    const slot = page.createForm?.fields.find((f) => f.key === 'slot');
    const values = slot?.options?.map((o) => o.value) ?? [];
    for (const v of ['home_brand_banner', 'home_sponsored_products', 'home_ad_row', 'home_advertorial', 'home_block:deals']) expect(values).toContain(v);
    await renderScreen(<MarketingView />);
    expect(await screen.findByRole('tab', { name: 'Home page layout' })).toBeInTheDocument();
  });
});
