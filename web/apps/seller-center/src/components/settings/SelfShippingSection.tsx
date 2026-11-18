'use client';

import { sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';

import { FulfilmentTip } from '@/components/ui/FulfilmentTip';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { Switch } from '@/components/ui/Switch';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

const CHARGE = /^\d{1,3}(\.\d{1,2})?$/;

/**
 * Your own shipping: one flat charge for each order. It exists only while AfroTransact has turned it on (a staff switch, off by
 * default): when it is off this renders nothing at all, so no shipping-charge field is shown anywhere. The wording says the
 * charge is the seller's own and their responsibility.
 */
export function SelfShippingSection({ editable }: { editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.selfShipping(sellerApi(), orgId));
  const [enabled, setEnabled] = useState(false);
  const [charge, setCharge] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (q.data) {
      setEnabled(q.data.enabled);
      setCharge((q.data.chargeMinor / 100).toFixed(2));
    }
  }, [q.data]);
  const save = useMutation({
    mutationFn: () => sellerApi().setSelfShipping({ enabled, chargeMinor: Math.round(Number(charge) * 100) }),
    onSuccess: () => {
      setError(null);
      toast.show(t('sc.selfShip.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.selfShipping(orgId) });
    },
    onError: (e) => setError(problemMessage(e))
  });
  if (!q.data?.available) return null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!CHARGE.test(charge) || Number(charge) > 100) {
      setError(t('sc.selfShip.badCharge'));
      return;
    }
    save.mutate();
  };
  return (
    <form noValidate onSubmit={submit} className="flex max-w-[40rem] flex-col gap-3 border-t border-border py-4" aria-label={t('sc.selfShip.title')} data-testid="self-shipping">
      <h2 className="m-0 text-md font-semibold">{t('sc.selfShip.title')}</h2>
      <div className="flex items-center">
        <Switch id="self-shipping-enabled" checked={enabled} disabled={!editable || save.isPending} onChange={setEnabled} label={t('sc.selfShip.toggle')} />
        <FulfilmentTip mode="sellerShips" />
      </div>
      {enabled ? <TextField label={t('sc.selfShip.charge')} help={t('sc.selfShip.help')} inputMode="decimal" value={charge} disabled={!editable} onChange={(e) => setCharge(e.currentTarget.value)} /> : null}
      {error ? <Notice tone="danger" title={error} role="alert" /> : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={save.isPending}>
            {t('sc.selfShip.save')}
          </Button>
        </div>
      ) : null}
    </form>
  );
}
