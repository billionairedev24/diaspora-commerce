import { type Metadata } from 'next';

import { LanguageSettingsView } from '@/components/settings/PersonalSettingsViews';

export const metadata: Metadata = { title: 'Language & number format' };

export default function Page() {
  return <LanguageSettingsView />;
}
