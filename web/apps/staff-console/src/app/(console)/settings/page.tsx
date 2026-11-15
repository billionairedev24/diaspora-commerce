import { type Metadata } from 'next';
import { redirect } from 'next/navigation';

import { SettingsHome } from '@/components/settings/SettingsHome';
import { settingsHref } from '@/lib/settings-sections';

export const metadata: Metadata = { title: 'Settings' };

/** The Settings home. The old `/settings?section=<key>&...` addresses (tours, bookmarks, links in emails) open the section's own page. */
export default async function SettingsRoute({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const params = await searchParams;
  const section = typeof params.section === 'string' ? params.section : null;
  if (section !== null) {
    const rest = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (k === 'section') return;
      (Array.isArray(v) ? v : v === undefined ? [] : [v]).forEach((x) => rest.append(k, x));
    });
    const target = settingsHref(section);
    const qs = rest.toString();
    redirect(qs ? `${target}${target.includes('?') ? '&' : '?'}${qs}` : target);
  }
  return <SettingsHome />;
}
