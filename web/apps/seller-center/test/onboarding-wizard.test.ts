import { type OnboardingStatus, type OnboardingStep } from '@afrotransact/api-client';
import { describe, expect, it } from 'vitest';

import { canOpen, nextSlug, prevSlug, progressOf, redirectFor, stepView } from '@/lib/onboarding-wizard';

const SLUGS = ['business', 'business-details', 'owners', 'stripe-verification', 'payouts', 'plan', 'catalog-compliance', 'review'] as const;
const KEYS = ['BUSINESS', 'BUSINESS_DETAILS', 'OWNERS', 'STRIPE_VERIFICATION', 'PAYOUTS', 'PLAN', 'CATALOG_COMPLIANCE', 'REVIEW'] as const;

function status(done: number, over: Partial<OnboardingStatus> = {}, editable = true): OnboardingStatus {
  const steps: OnboardingStep[] = SLUGS.map((slug, i) => ({
    key: KEYS[i] ?? 'REVIEW',
    slug,
    label: slug,
    sub: '',
    state: i < done ? 'DONE' : 'TODO'
  }));
  return {
    orgId: 'org_new',
    kind: 'SELLER',
    status: editable ? 'DRAFT' : 'PENDING_REVIEW',
    reference: 'APP-1',
    steps,
    requestedItems: [],
    canResume: editable,
    resumeStep: SLUGS[Math.min(done, SLUGS.length - 1)] ?? 'review',
    submittedAt: null,
    stripe: {
      status: 'NOT_STARTED',
      detailsSubmitted: false,
      chargesEnabled: false,
      payoutsEnabled: false,
      identityStatus: null,
      taxIdStatus: null,
      currentlyDue: []
    },
    timeline: [],
    form: { editable } as OnboardingStatus['form'],
    ...over
  };
}

describe('wizard navigation', () => {
  it('resumes at the first incomplete step and blocks the ones after it', () => {
    const st = status(2);
    expect(redirectFor(st, null)).toBe('/onboarding/owners');
    expect(redirectFor(st, 'business')).toBeNull();
    expect(redirectFor(st, 'owners')).toBeNull();
    expect(redirectFor(st, 'plan')).toBe('/onboarding/owners');
    expect(redirectFor(st, 'nonsense')).toBe('/onboarding/owners');
    expect(canOpen(st, 'business-details')).toBe(true);
    expect(canOpen(st, 'payouts')).toBe(false);
  });

  it('shows done / current / todo / blocked in the stepper', () => {
    const st = status(2);
    const views = st.steps.map((s) => stepView(st, s, 'business'));
    expect(views).toEqual(['current', 'done', 'todo', 'blocked', 'blocked', 'blocked', 'blocked', 'blocked']);
  });

  it('moves forward and back in order, and counts "Step n of N"', () => {
    const st = status(3);
    expect(nextSlug(st, 'owners')).toBe('stripe-verification');
    expect(prevSlug(st, 'owners')).toBe('business-details');
    expect(prevSlug(st, 'business')).toBeNull();
    expect(nextSlug(st, 'review')).toBeNull();
    expect(progressOf(st, 'owners')).toEqual({
      num: 3,
      total: 8,
      label: 'owners',
      pct: 38
    });
  });

  it('a submitted application shows its status; only Review stays readable', () => {
    const st = status(7, {}, false);
    expect(redirectFor(st, null)).toBeNull();
    expect(redirectFor(st, 'review')).toBeNull();
    expect(redirectFor(st, 'business')).toBe('/onboarding');
  });

  it('a step reopened for more information can be opened', () => {
    const st = status(5);
    const info = {
      ...st,
      steps: st.steps.map((s) => (s.slug === 'catalog-compliance' ? { ...s, state: 'ACTION' as const } : s))
    };
    expect(canOpen(info, 'catalog-compliance')).toBe(true);
    expect(stepView(info, info.steps[6] as OnboardingStep, 'business')).toBe('action');
  });
});
