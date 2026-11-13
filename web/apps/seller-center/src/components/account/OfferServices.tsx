'use client';

import { SettingRow } from '@afrotransact/account';
import { Button } from '@afrotransact/ui';
import { useRouter } from 'next/navigation';

import { type MessageKey, useT } from '@/i18n/t';
import { entryDecision } from '@/lib/onboarding-entry';
import { useSeller } from '@/lib/session';

/**
 * Settings › Services. Selling products needs no department application, but offering services does: it is a
 * separate application and approval (a Provider Hub organisation), made from the same sign-in as this store. Nothing
 * is shown inside Provider Hub, which is already the services side.
 */
export function OfferServices() {
  const t = useT();
  const router = useRouter();
  const { session, isProvider } = useSeller();
  if (isProvider || !session.activeOrg || session.activeOrg.kind !== 'SELLER') return null;
  const decision = entryDecision(session, 'provider');
  const key = decision.action === 'home' ? 'open' : decision.action === 'resume' ? 'continue' : 'apply';
  return (
    <section aria-label={t('sc.services.offer.title')}>
      <SettingRow label={t('sc.services.offer.title')} hint={t(`sc.services.offer.${key}.hint` as MessageKey)} end>
        <Button variant="neutral" type="button" onClick={() => router.push('/onboarding?role=provider')}>
          {t(`sc.services.offer.${key}.action` as MessageKey)}
        </Button>
      </SettingRow>
    </section>
  );
}
