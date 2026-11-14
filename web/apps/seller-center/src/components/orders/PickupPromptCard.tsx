'use client';

import { type PickupPromptAnswer, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, buttonClassName } from '@afrotransact/ui';
import { Storefront } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { cardVisible, nudgeVisible } from '@/lib/pickup-prompt';
import { useSeller } from '@/lib/session';

/**
 * The prompt on Home: pickup is off, and turning it on happens in Settings > Pickup (the seller's address is already there;
 * they only confirm hours, ready time and that someone will be there). This card never switches anything on. "Not now" and
 * "Don't ask again" are remembered by the server per organisation, so they hold on every device.
 */
export function PickupPromptCard() {
  const t = useT();
  const qc = useQueryClient();
  const { orgId, can } = useSeller();
  const prompt = useQuery(sellerQueries.pickupPrompt(sellerApi(), orgId));
  const [problem, setProblem] = useState<string | null>(null);
  const answer = useMutation({
    mutationFn: (a: PickupPromptAnswer) => sellerApi().answerPickupPrompt(a),
    onSuccess: () => {
      setProblem(null);
      void qc.invalidateQueries({ queryKey: sellerKeys.pickupPrompt(orgId) });
    },
    onError: () => setProblem(t('sc.pickupCard.answerErr'))
  });
  const data = prompt.data;
  if (!data || !data.address || !can('orders', 'EDIT') || !cardVisible(data)) return null;
  return (
    <Card
      title={
        <span className="flex items-center gap-2">
          <Storefront size={18} aria-hidden /> {t('sc.pickupCard.title')}
        </span>
      }
    >
      <div className="flex flex-col gap-3" data-testid="pickup-prompt" role="group" aria-label={t('sc.pickupCard.title')}>
        <p className="m-0 text-sm">{t('sc.pickupCard.intro')}</p>
        <p className="m-0 text-sm text-muted">{t('sc.pickupCard.offNote')}</p>
        {problem ? (
          <Notice tone="danger" title={problem} role="alert" />
        ) : null}
        <div className="flex flex-wrap gap-2">
          <Link href="/settings/pickup" className={buttonClassName({ variant: 'primary' })}>
            {t('sc.pickupCard.nudgeLink')}
          </Link>
          <Button variant="neutral" disabled={answer.isPending} onClick={() => answer.mutate('NOT_NOW')}>
            {t('sc.pickupCard.notNow')}
          </Button>
          <Button variant="neutral" disabled={answer.isPending} onClick={() => answer.mutate('DONT_ASK_AGAIN')}>
            {t('sc.pickupCard.dontAsk')}
          </Button>
        </div>
      </div>
    </Card>
  );
}

/** A small inline reminder on Orders: shown only while the seller has no pickup place, with a link to Settings > Pickup. */
export function PickupNudge() {
  const t = useT();
  const { orgId, can } = useSeller();
  const prompt = useQuery(sellerQueries.pickupPrompt(sellerApi(), orgId));
  if (!can('orders', 'VIEW') || !nudgeVisible(prompt.data)) return null;
  return (
    <Notice
      tone="info"
      title={t('sc.pickupCard.nudge')}
      action={
        <Link href="/settings/pickup" className="text-link underline">
          {t('sc.pickupCard.nudgeLink')}
        </Link>
      }
    />
  );
}
