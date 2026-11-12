'use client';

import { staffTaxQueries } from '@afrotransact/api-client';
import { Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

/**
 * How many live listings have no stored, resolved tax decision. Always 0 when the tax gate holds (a product is never live
 * without one). A failed read counts as 0 here: the banner only ever adds a warning, the Prometheus alert covers the rest.
 */
export function useLiveWithoutDecisionCount(): number {
  const api = staffApi();
  const query = useQuery(staffTaxQueries.liveWithoutDecision(api.tax, { page: 0, size: 1 }));
  return query.data?.total ?? 0;
}

/** The red banner on Tax review: shown only when a listing is live without a tax decision, with a link to the list. */
export function TaxGateBanner({ onShow }: { onShow: () => void }) {
  const t = useT();
  const count = useLiveWithoutDecisionCount();
  if (count < 1) return null;
  return (
    <div data-testid="tax-gate-banner">
      <Notice tone="danger" role="alert" title={t('st.tax.gate.banner.title', { n: count })} action={<Button variant="link" onClick={onShow}>{t('st.tax.gate.banner.show')}</Button>}>
        {t('st.tax.gate.banner.body')}
      </Notice>
    </div>
  );
}
