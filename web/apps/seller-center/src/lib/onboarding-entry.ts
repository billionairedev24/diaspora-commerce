import { type OrgKind, type OrgSummary, type SellerSession } from '@afrotransact/api-client';

/* Arriving from the storefront's "Start selling" / "Offer your services" (/onboarding?role=seller|provider, S286):
   decide what the signed-in person gets without asking them anything. */

export type ApplyRole = 'seller' | 'provider';

/** The role asked for in the query string (`?role=seller|provider`), or null. */
export function applyRoleOf(search: string): ApplyRole | null {
  const role = new URLSearchParams(search).get('role');
  return role === 'seller' || role === 'provider' ? role : null;
}

export const kindOf = (role: ApplyRole): OrgKind => (role === 'provider' ? 'PROVIDER' : 'SELLER');

export type EntryDecision =
  /** Already approved for that kind: open the back office (switching organisation first when `switchTo` is set). */
  | { action: 'home'; switchTo: string | null }
  /** An application of that kind is in progress: continue it (switching to its organisation first when needed; the
      idempotent start resumes it and fills answers still empty from the account). */
  | { action: 'resume'; switchTo: string | null }
  /** Nothing yet: start the application (adds the applicant role; idempotent on the server). */
  | { action: 'start' };

const IN_PROGRESS = new Set(['DRAFT', 'PENDING_REVIEW', 'FLAGGED', 'AWAITING_FINAL_APPROVAL', 'INFO_REQUESTED']);

export function entryDecision(session: SellerSession, role: ApplyRole): EntryDecision {
  const kind = kindOf(role);
  const activeId = session.activeOrg?.id ?? null;
  const pick = (match: (o: OrgSummary) => boolean): OrgSummary | null =>
    (session.activeOrg && match(session.activeOrg) ? session.activeOrg : null) ?? session.orgs.find(match) ?? null;
  const approved = pick((o) => o.kind === kind && o.approved);
  if (approved) return { action: 'home', switchTo: approved.id === activeId ? null : approved.id };
  const pending = pick((o) => o.kind === kind && IN_PROGRESS.has(o.status));
  if (pending) return { action: 'resume', switchTo: pending.id === activeId ? null : pending.id };
  return { action: 'start' };
}
