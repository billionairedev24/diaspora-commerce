import { type OrgSummary, type SellerSession } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { applyRoleOf, entryDecision } from '@/lib/onboarding-entry';

const org = (id: string, kind: 'SELLER' | 'PROVIDER', status: OrgSummary['status']): OrgSummary => ({
  id, kind, name: id, status, approved: status === 'APPROVED', roleKey: `${kind.toLowerCase()}.owner`, roleLabel: 'Owner'
});
const session = (orgs: OrgSummary[], active: OrgSummary | null = orgs[0] ?? null): SellerSession => ({
  user: { id: 'u', firstName: 'A', lastName: 'B', email: 'a@b.c', initials: 'AB' },
  orgs, activeOrg: active, portal: null, approved: !!active?.approved, navGroups: [], landing: 'home', accessNote: '', capabilities: []
});

describe('Start selling arrival (/onboarding?role=…)', () => {
  it('reads only seller or provider from the query string', () => {
    expect(applyRoleOf('?role=seller')).toBe('seller');
    expect(applyRoleOf('?x=1&role=provider')).toBe('provider');
    expect(applyRoleOf('?role=admin')).toBeNull();
    expect(applyRoleOf('')).toBeNull();
  });

  it('a customer without an organisation starts an application', () => {
    expect(entryDecision(session([]), 'seller')).toEqual({ action: 'start' });
  });

  it('an approved seller opens Seller Center home, switching organisation when another one is active', () => {
    const seller = org('s', 'SELLER', 'APPROVED');
    const provider = org('p', 'PROVIDER', 'APPROVED');
    expect(entryDecision(session([seller, provider], seller), 'seller')).toEqual({ action: 'home', switchTo: null });
    expect(entryDecision(session([seller, provider], provider), 'seller')).toEqual({ action: 'home', switchTo: 's' });
    // An approved seller offering services for the first time applies for that.
    expect(entryDecision(session([seller], seller), 'provider')).toEqual({ action: 'start' });
  });

  it('an application in progress is resumed, not started again', () => {
    const draft = org('d', 'SELLER', 'DRAFT');
    const info = org('i', 'PROVIDER', 'INFO_REQUESTED');
    expect(entryDecision(session([draft]), 'seller')).toEqual({ action: 'resume', switchTo: null });
    expect(entryDecision(session([draft, info], draft), 'provider')).toEqual({ action: 'resume', switchTo: 'i' });
    expect(entryDecision(session([org('r', 'SELLER', 'REJECTED')]), 'seller')).toEqual({ action: 'start' });
  });
});
