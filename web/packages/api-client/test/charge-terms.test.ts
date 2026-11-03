import { describe, expect, it } from 'vitest';

import { chargePolicyLimits, chargePolicyOf, chargeTermsOf } from '../src/staff-plans';

describe('plan charge terms', () => {
  it('reads and writes the charge keys of a plan', () => {
    const limits = { listings: '1000', chargeFreeMonths: '3', chargeThen: 'CHARGE_WHEN', chargeMatch: 'ANY', chargeClauses: 'GROSS_SALES:100000,ACTIVE_LISTINGS:20' };
    const p = chargePolicyOf(limits);
    expect(p).toMatchObject({ freeMonths: 3, then: 'CHARGE_WHEN', match: 'ANY', windowMonths: null });
    expect(p.clauses).toEqual([{ metric: 'GROSS_SALES', threshold: 100000 }, { metric: 'ACTIVE_LISTINGS', threshold: 20 }]);
    expect(chargePolicyLimits(p)).toEqual({ chargeFreeMonths: '3', chargeThen: 'CHARGE_WHEN', chargeMatch: 'ANY', chargeClauses: 'GROSS_SALES:100000,ACTIVE_LISTINGS:20' });
    expect(chargePolicyLimits(chargePolicyOf({}))).toEqual({});
  });

  it('says what a plan charges in words', () => {
    expect(chargeTermsOf({}, 3900)).toBe('$39/mo');
    expect(chargeTermsOf({ chargeFreeMonths: '3' }, 3900)).toBe('First 3 months free, then $39/mo');
    expect(chargeTermsOf({ chargeThen: 'CHARGE_WHEN', chargeClauses: 'GROSS_SALES:100000' }, 3900)).toBe('free until you reach $1,000 in sales');
    expect(chargeTermsOf({ chargeFreeMonths: '1', chargeThen: 'WAIVE_WHILE', chargeMatch: 'ALL', chargeClauses: 'ACTIVE_LISTINGS:20,GROSS_SALES:50000' }, 3900))
      .toBe('First month free, then $39/mo, free while you have 20 active listings and reach $500 in sales');
    expect(chargeTermsOf({ chargeFreeMonths: '3' }, 0)).toBe('No monthly fee — commission only');
  });
});
