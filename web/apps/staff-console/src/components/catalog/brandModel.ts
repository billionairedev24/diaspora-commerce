import { type BrandStatus, splitBrandList,type StaffBrand } from '@afrotransact/api-client';

/* Pure rules of the Brands tab: what a pasted list will do before anything is saved, and which actions a brand offers. */

export const BRAND_STATUSES: BrandStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'MERGED_INTO', 'RETIRED'];

export type BrandAction = 'approve' | 'reject' | 'merge' | 'edit' | 'retire';

/** The decisions open to a brand in its status (the server refuses the rest). */
export function brandActions(status: BrandStatus): BrandAction[] {
  switch (status) {
    case 'PENDING':
      return ['approve', 'reject', 'merge', 'edit'];
    case 'APPROVED':
      return ['merge', 'edit', 'retire'];
    case 'REJECTED':
    case 'RETIRED':
      return ['approve', 'edit'];
    default:
      return [];
  }
}

export type PreviewOutcome = 'NEW' | 'EXISTS' | 'INVALID';

export interface BrandPreviewRow {
  name: string;
  outcome: PreviewOutcome;
  /** The brand it matches, by name (never an id). */
  match: string | null;
}

export interface BrandPreview {
  rows: BrandPreviewRow[];
  /** Lines left out because the same name was already in the paste. */
  repeated: number;
  fresh: number;
  existing: number;
  invalid: number;
}

const keyOf = (s: string): string => s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');

/**
 * What saving a pasted list would do: names that match a brand already in the registry (case, accents and punctuation
 * ignored), names that are too short or long, and the rest, which are new. The server decides again on save.
 */
export function previewBrandList(text: string, known: Pick<StaffBrand, 'name' | 'status'>[]): BrandPreview {
  const names = splitBrandList(text);
  const raw = text.split(/[\r\n,;]+/).filter((l) => l.trim() !== '').length;
  const live = new Map<string, string>();
  known.filter((b) => b.status === 'APPROVED' || b.status === 'PENDING').forEach((b) => live.set(keyOf(b.name), b.name));
  const rows = names.map<BrandPreviewRow>((name) => {
    if (name.length < 2 || name.length > 120) return { name, outcome: 'INVALID', match: null };
    const match = live.get(keyOf(name));
    return match ? { name, outcome: 'EXISTS', match } : { name, outcome: 'NEW', match: null };
  });
  return {
    rows,
    repeated: Math.max(0, raw - names.length),
    fresh: rows.filter((r) => r.outcome === 'NEW').length,
    existing: rows.filter((r) => r.outcome === 'EXISTS').length,
    invalid: rows.filter((r) => r.outcome === 'INVALID').length
  };
}
