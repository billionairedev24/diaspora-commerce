'use client';

import { sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { Button, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';
import { addressFromBusiness } from '@/lib/settings-sections';

/**
 * "Use my business address": the first location starts from the address saved at sign-up (made the default), so the seller
 * does not type it again. Shown only while there is no location and the business address is complete; nothing creates a
 * location without this click.
 */
export function BusinessAddressStart() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const onboarding = useQuery({ ...sellerQueries.onboarding(sellerApi(), orgId), retry: false });
  const form = onboarding.data?.form;
  const address = addressFromBusiness(form?.details.address);
  const start = useMutation({
    mutationFn: () => {
      if (!address || !form) throw new Error('no business address');
      const name = [form.details.legalFirstName, form.details.legalLastName].filter(Boolean).join(' ') || form.details.legalName || form.business.storeName || '';
      return sellerApi().createShipFrom({ ...address, name, phone: form.business.phone ?? '', label: form.business.storeName ?? '', makeDefault: true });
    },
    onSuccess: () => {
      toast.show(t('sc.shipFrom.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.pickupPrompt(orgId) });
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  if (!address) return null;
  return (
    <Button loading={start.isPending} onClick={() => start.mutate()}>
      {t('sc.settings.locations.useBusiness', { address: `${address.line1}, ${address.city}, ${address.state} ${address.postalCode}` })}
    </Button>
  );
}
