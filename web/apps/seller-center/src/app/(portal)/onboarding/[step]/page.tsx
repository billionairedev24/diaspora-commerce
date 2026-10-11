import { type Metadata } from 'next';

import { OnboardingWizard } from '@/components/onboarding/OnboardingView';

export const metadata: Metadata = { title: 'Onboarding' };

/** One step of the application wizard: /onboarding/<step> (refresh and the browser's back button keep the step). */
export default async function OnboardingStepPage({ params }: { params: Promise<{ step: string }> }) {
  const { step } = await params;
  return <OnboardingWizard slug={step} />;
}
