'use client';

import { type StaffSession } from '@afrotransact/api-client';
import { TourProvider } from '@afrotransact/tour/react';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useCallback, useMemo } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { STAFF_TOURS } from '@/tours/staff-tours';
import { staffGuards, staffSubject, tourLabels } from '@/tours/subject';

/**
 * Guided tours for the console: gives the shell's header (Help menu) and pages the tour service. First-time tours start by
 * themselves on their page, once, when nothing else is open (see @afrotransact/tour). Must sit inside the session provider.
 */
export function StaffTourHost({ session, children }: { session: StaffSession; children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const subject = useMemo(() => staffSubject(session), [session]);
  const guards = useMemo(() => staffGuards(session), [session]);
  const labels = useMemo(() => tourLabels(t), [t]);
  const translate = useCallback((key: string) => t(key as MessageKey), [t]);
  const navigate = useCallback((route: string) => router.push(route), [router]);
  return (
    <TourProvider tours={STAFF_TOURS} subject={subject} translate={translate} labels={labels} pathname={pathname} navigate={navigate} guards={guards} autoStart>
      {children}
    </TourProvider>
  );
}
