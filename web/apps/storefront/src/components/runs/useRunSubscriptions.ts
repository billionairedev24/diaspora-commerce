'use client';

import { deliveryRunKeys, deliveryRunQueries, isApiError, type RunChannel, type RunSubscription } from '@afrotransact/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

/**
 * The signed-in shopper's delivery alert subscriptions and the one action the screens share: turn a channel on or off for
 * a location (consent recorded or withdrawn in delivery-runs). `pending` is the channel being saved.
 */
export function useRunSubscriptions(source: string) {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const mine = useQuery(deliveryRunQueries.mine(api));
  const [pending, setPending] = useState<{ locationId: string; channel: RunChannel } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const toggle = useMutation({
    mutationFn: ({ locationId, channel, on }: { locationId: string; channel: RunChannel; on: boolean }): Promise<RunSubscription> =>
      on ? api.subscribeMyRunAlerts({ locationId, channels: [channel], source }) : api.unsubscribeMyRunAlerts(locationId, [channel]),
    onMutate: ({ locationId, channel }) => {
      setError(null);
      setPending({ locationId, channel });
    },
    onSuccess: (sub) => {
      qc.setQueryData(deliveryRunKeys.mine(), (cur: { items: RunSubscription[] } | undefined) => ({
        items: [...(cur?.items ?? []).filter((s) => s.locationId !== sub.locationId), sub]
      }));
    },
    onError: (e) => setError(isApiError(e) ? (Object.values(e.fieldErrors)[0] ?? e.message) : t('runs.error.generic')),
    onSettled: () => {
      setPending(null);
      void qc.invalidateQueries({ queryKey: deliveryRunKeys.mine() });
    }
  });
  return {
    mine,
    subscriptions: mine.data?.items ?? [],
    pending,
    error,
    toggle: (locationId: string, channel: RunChannel, on: boolean) => toggle.mutate({ locationId, channel, on })
  };
}
