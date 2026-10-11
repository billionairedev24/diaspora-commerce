import { type Metadata } from 'next';

import { OnboardingView } from '@/components/onboarding/OnboardingView';

export const metadata: Metadata = { title: 'Onboarding' };

/** Resumes the application at its first incomplete step, or shows the submitted application's status. */
export default function OnboardingPage() {
  return <OnboardingView />;
}
