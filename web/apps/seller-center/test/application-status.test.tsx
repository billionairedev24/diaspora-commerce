import { type OnboardingStatus } from '@afrotransact/api-client';
import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { returnedMessage } from '@/components/onboarding/OnboardingView';
import { SubmittedView } from '@/components/onboarding/SubmittedView';

import { renderWithProviders } from './render';

const status = (over: Partial<OnboardingStatus>): OnboardingStatus =>
  ({
    orgId: 'org_1', kind: 'SELLER', status: 'PENDING_REVIEW', reference: 'APP-30200', steps: [], requestedItems: [], canResume: false, resumeStep: 'review',
    submittedAt: '2026-10-20T10:00:00Z', stripe: { status: 'VERIFIED' }, timeline: [], form: {},
    ...over
  }) as unknown as OnboardingStatus;

describe('what the applicant sees of their application', () => {
  it('finds the message the reviewer left when the application was returned', () => {
    const s = status({
      status: 'INFO_REQUESTED',
      timeline: [
        { action: 'SUBMITTED', status: 'PENDING_REVIEW', note: null, occurredAt: '2026-10-20T10:00:00Z' },
        { action: 'REQUEST_INFO', status: 'INFO_REQUESTED', note: 'Please upload a clearer photo of your ID', occurredAt: '2026-10-21T10:00:00Z' }
      ]
    });
    expect(returnedMessage(s)).toBe('Please upload a clearer photo of your ID');
    expect(returnedMessage(status({ timeline: [] }))).toBeNull();
  });

  it('shows the reason an application was not approved', () => {
    const s = status({
      status: 'REJECTED',
      timeline: [{ action: 'REJECT', status: 'REJECTED', note: 'We cannot verify your business address', occurredAt: '2026-10-22T10:00:00Z' }]
    });
    renderWithProviders(<SubmittedView status={s} onRefresh={() => undefined} refreshing={false} />);
    expect(screen.getByText('We cannot verify your business address')).toBeInTheDocument();
  });

  it('a flagged application looks like any application in review: the applicant is not told about the flag', () => {
    renderWithProviders(<SubmittedView status={status({ status: 'FLAGGED' })} onRefresh={() => undefined} refreshing={false} />);
    expect(screen.getByText(/is with our team/)).toBeInTheDocument();
    expect(screen.queryByText(/flag/i)).not.toBeInTheDocument();
  });
});
