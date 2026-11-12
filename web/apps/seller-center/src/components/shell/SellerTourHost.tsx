'use client';

import { type SellerSession } from '@afrotransact/api-client';
import { TourProvider } from '@afrotransact/tour/react';
import { usePathname, useRouter } from 'next/navigation';
import { type ReactNode, useCallback, useMemo } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { sellerTourRemote, TOUR_NAMESPACE } from '@/tours/remote';
import { SELLER_JOURNEYS, SELLER_TOURS } from '@/tours/seller-tours';
import { sellerGuards, sellerSubject, TOUR_HUB_HREF, tourLabels } from '@/tours/subject';

/**
 * Guided tours for the back office: gives the shell's header (Help menu), the Tour hub and the pages the tour service. The
 * person's progress comes from the server first (so it follows them to any browser), and only then may a first-time tour start by
 * itself, once, on its page, when nothing else is open (see @afrotransact/tour). Must sit inside the session provider.
 */
export function SellerTourHost({ session, children }: { session: SellerSession; children: ReactNode }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const subject = useMemo(() => sellerSubject(session), [session]);
  const guards = useMemo(() => sellerGuards(session), [session]);
  const labels = useMemo(() => tourLabels(t), [t]);
  const remote = useMemo(() => sellerTourRemote(), []);
  const translate = useCallback((key: string) => t(key as MessageKey), [t]);
  const navigate = useCallback((route: string) => router.push(route), [router]);
  return (
    <TourProvider
      tours={SELLER_TOURS}
      journeys={SELLER_JOURNEYS}
      subject={subject}
      translate={translate}
      labels={labels}
      pathname={pathname}
      navigate={navigate}
      guards={guards}
      remote={remote}
      namespace={TOUR_NAMESPACE}
      hubHref={TOUR_HUB_HREF}
      autoStart
    >
      {children}
    </TourProvider>
  );
}
