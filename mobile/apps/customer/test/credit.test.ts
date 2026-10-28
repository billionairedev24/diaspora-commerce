import { CreditAccountSchema } from '@afrotransact/api-client';
import { beforeEach, describe, expect, it } from 'vitest';

import { resetMockDb } from '../src/api/mock/db';
import { mockFetch } from '../src/api/mock/router';
import { EN_MESSAGES } from '../src/i18n/catalogs';

const req = (path: string) =>
  mockFetch(new Request(`http://localhost:8200${path}`, { headers: { Authorization: 'Bearer t' } }));

describe('AfroTransact credit in the app (S320)', () => {
  beforeEach(() => resetMockDb());

  it('shows a balance that is the newest entry and a history newest first, in the contract shape', async () => {
    const account = CreditAccountSchema.parse(await (await req('/v1/account/credit')).json());
    expect(account.entries.length).toBeGreaterThan(1);
    expect(account.balanceMinor).toBe(account.entries[0]?.balanceAfterMinor);
    expect(account.entries.map((e) => e.createdAt)).toEqual([...account.entries.map((e) => e.createdAt)].sort().reverse());
    expect(account.nextBefore).toBeNull();
  });

  it('has a label for every source of credit and nothing in the app moves credit out', () => {
    for (const source of ['REFERRAL', 'GIFT_CARD', 'MEMBERSHIP', 'REFUND', 'STAFF', 'PROMO', 'ORDER']) {
      expect(EN_MESSAGES).toHaveProperty([`cu.credit.src.${source}`]);
    }
    const creditKeys = Object.keys(EN_MESSAGES).filter((k) => k.startsWith('cu.credit.'));
    expect(creditKeys.filter((k) => /cash|withdraw|payout|transfer/i.test(k))).toEqual([]);
    expect(EN_MESSAGES['cu.credit.intro']).toMatch(/can’t be withdrawn/);
  });
});
