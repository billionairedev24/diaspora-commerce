import { type OnboardingStatus, type OnboardingStep } from '@afrotransact/api-client';

/* Navigation of the application wizard (S289): the steps the application has (Owners only for businesses with
   owners), where to resume, which steps can be opened and what comes next. The URL is /onboarding/<slug>. */

/** How a step shows in the stepper. */
export type StepView = 'done' | 'current' | 'todo' | 'blocked' | 'action';

export const onboardingHref = (slug: string): string => `/onboarding/${slug}`;

/** Whether answers can still change (a draft, or a request for more information). */
export const isEditable = (st: OnboardingStatus): boolean => st.form.editable;

/** Index of the step the application resumes at (the first incomplete one; Review & submit once all are done). */
export function resumeIndex(st: OnboardingStatus): number {
  const i = st.steps.findIndex((s) => s.slug === st.resumeStep);
  return i < 0 ? st.steps.length - 1 : i;
}

/**
 * Steps up to the first incomplete one can be opened; later ones are blocked until the earlier ones are complete
 * (completed steps can always be revisited).
 */
export function canOpen(st: OnboardingStatus, slug: string): boolean {
  const i = st.steps.findIndex((s) => s.slug === slug);
  if (i < 0) return false;
  const step = st.steps[i];
  return step?.state === 'DONE' || step?.state === 'ACTION' || i <= resumeIndex(st);
}

export function stepView(st: OnboardingStatus, step: OnboardingStep, current: string): StepView {
  if (step.slug === current) return 'current';
  if (step.key !== 'REVIEW' && step.state === 'DONE') return 'done';
  if (step.state === 'ACTION') return 'action';
  return canOpen(st, step.slug) ? 'todo' : 'blocked';
}

/** The step after `slug` (null on the last one). */
export function nextSlug(st: OnboardingStatus, slug: string): string | null {
  const i = st.steps.findIndex((s) => s.slug === slug);
  return i >= 0 && i + 1 < st.steps.length ? (st.steps[i + 1]?.slug ?? null) : null;
}

/** The step before `slug` (null on the first one). */
export function prevSlug(st: OnboardingStatus, slug: string): string | null {
  const i = st.steps.findIndex((s) => s.slug === slug);
  return i > 0 ? (st.steps[i - 1]?.slug ?? null) : null;
}

/** "Step 3 of 8 · Owners" and the progress bar's share. */
export function progressOf(st: OnboardingStatus, slug: string): { num: number; total: number; label: string; pct: number } {
  const i = Math.max(
    0,
    st.steps.findIndex((s) => s.slug === slug)
  );
  const total = st.steps.length;
  return {
    num: i + 1,
    total,
    label: st.steps[i]?.label ?? '',
    pct: Math.round(((i + 1) / total) * 100)
  };
}

/**
 * Where /onboarding/<slug> should be: the wizard's resume step for an unknown or blocked step, the submitted view
 * (/onboarding) when the application can no longer change (its answers stay readable on Review), or null to stay.
 */
export function redirectFor(st: OnboardingStatus, slug: string | null): string | null {
  if (!isEditable(st)) return slug === null || slug === 'review' ? null : '/onboarding';
  if (slug === null || !canOpen(st, slug)) return onboardingHref(st.resumeStep);
  return null;
}
