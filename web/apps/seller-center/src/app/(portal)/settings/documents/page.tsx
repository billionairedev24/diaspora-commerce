import { type Metadata } from 'next';

import { DocumentsSettingsView } from '@/components/settings/MoneySettingsViews';

export const metadata: Metadata = { title: 'Documents & compliance' };

export default function Page() {
  return <DocumentsSettingsView />;
}
