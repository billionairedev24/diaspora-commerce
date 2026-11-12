'use client';

import { type SellerSession } from '@afrotransact/api-client';
import { TourProvider } from '@afrotransact/tour/react';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useCallback, useMemo } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { SELLER_TOURS } from '@/tours/seller-tours';
import { sellerGuards, sellerSubject, tourLabels } from '@/tours/subject';

/**
 * Guided tours for the back office: gives the shell's header (Help menu) and pages the tour service. First-time tours start by
 * themselves on their page, once, when nothing else is open (see @afrotransact/tour). Must sit inside the session provider.
 */
export function SellerTourHost({ session, children }: { session: SellerSession; children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const subject = useMemo(() => sellerSubject(session), [session]);
  const guards = useMemo(() => sellerGuards(session), [session]);
  const labels = useMemo(() => tourLabels(t), [t]);
  const translate = useCallback((key: string) => t(key as MessageKey), [t]);
  const navigate = useCallback((route: string) => router.push(route), [router]);
  return (
    <TourProvider tours={SELLER_TOURS} subject={subject} translate={translate} labels={labels} pathname={pathname} navigate={navigate} guards={guards} autoStart>
      {children}
    </TourProvider>
  );
}
