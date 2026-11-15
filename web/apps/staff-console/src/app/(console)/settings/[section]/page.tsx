import { type Metadata } from 'next';
import { notFound } from 'next/navigation';

import { SettingsSectionScreen } from '@/components/settings/SettingsSectionScreen';
import { SETTINGS_SECTIONS } from '@/lib/settings-sections';

export const metadata: Metadata = { title: 'Settings' };

export default async function SettingsSectionRoute({ params }: { params: Promise<{ section: string }> }) {
  const { section } = await params;
  const found = SETTINGS_SECTIONS.find((s) => s.own && s.key === section);
  if (!found) notFound();
  return <SettingsSectionScreen section={found.key} />;
}
