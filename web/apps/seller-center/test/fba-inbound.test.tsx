import { describe, expect, it } from 'vitest';

import { planErrors } from '@/components/inventory/FbaInbound';

const line = (over: Record<string, unknown> = {}) => ({ key: 'k', atin: 'ATABCDEF12', qty: '24', casePack: '12', lotNo: '', mfgDate: '', expDate: '', perishable: false, ...over });

describe('Send to AfroTransact plan rules (S281)', () => {
  const today = '2026-10-11';
  it('requires a batch and an expiry date for food and perishables', () => {
    expect(planErrors([line({ perishable: true })], today)).toMatchObject({ '0.lotNo': expect.stringMatching(/required/), '0.expDate': expect.stringMatching(/required/) });
    expect(planErrors([line({ perishable: true, lotNo: 'B-1', expDate: '2027-06-01' })], today)).toEqual({});
  });
  it('sends whole cases of valid ATINs with sane dates', () => {
    expect(planErrors([line({ qty: '20' })], today)['0.qty']).toMatch(/multiple of 12/);
    expect(planErrors([line({ atin: 'ATX' })], today)['0.atin']).toBeDefined();
    expect(planErrors([line({ expDate: '2026-01-01' })], today)['0.expDate']).toMatch(/expired/);
    expect(planErrors([line({ mfgDate: '2026-12-01' })], today)['0.mfgDate']).toMatch(/future/);
  });
});
