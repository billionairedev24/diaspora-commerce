'use client';

import { cartKeys, type PreorderFallback, preorderKeys, type PreorderPlace, type PreorderStage, productKeys, searchKeys, siteKeys } from '@afrotransact/api-client';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState } from 'react';

import { browserApi } from '@/lib/api/browser';
import { BFF_BASE_PATH } from '@/lib/config';
import { usePreorderResolution } from '@/lib/preorder/usePreorder';

import styles from './Preorder.module.css';

/* Dev / API_MOCKS only — never rendered in production builds against the real BFF. The prototype's "Preview as" bar
   (design/AfroTransact Pre-Order v2.dc.html) and the Shop's "Preview pre-order city": switch the delivery city and
   force a stage, the master switch or the fallback so every storefront state can be seen with the mock BFF.
   English only on purpose (developer tool). */

interface Preview {
  place?: PreorderPlace;
  stage?: PreorderStage;
  enabled?: boolean;
  fallback?: PreorderFallback;
}

const CITIES: { label: string; zip?: string; place?: PreorderPlace }[] = [
  { label: 'Houston, TX (next-day metro)', zip: '77042' },
  { label: 'Lubbock, TX (#7 open)', zip: '79401' },
  { label: 'Amarillo, TX (#4 closed)', zip: '79101' },
  { label: 'El Paso, TX (#3 upcoming)', zip: '79901' },
  { label: 'Midland, TX (#2 draft)', zip: '79701' },
  { label: 'Toronto, ON (#1 upcoming, CAD)', place: { country: 'CA', region: 'ON', city: 'Toronto' } }
];

const previewUrl = `${BFF_BASE_PATH}/v1/mock/preorder-preview`;

async function putPreview(p: Preview): Promise<Preview> {
  const res = await fetch(previewUrl, { method: 'PUT', credentials: 'include', headers: { 'Content-Type': 'application/json', Accept: 'application/json' }, body: JSON.stringify(p) });
  return (await res.json()) as Preview;
}

export function PreorderPreview() {
  const qc = useQueryClient();
  const id = useId();
  const { data: r } = usePreorderResolution();
  const { data: current } = useQuery({ queryKey: [...preorderKeys.all, 'mock-preview'], queryFn: async () => (await (await fetch(previewUrl, { credentials: 'include' })).json()) as Preview });
  const [busy, setBusy] = useState(false);
  const preview = current ?? {};
  const cityIdx = Math.max(0, CITIES.findIndex((c) => (c.place ? preview.place?.city === c.place.city : !preview.place && c.label.startsWith(`${r?.place.city ?? 'Houston'},`))));

  const apply = async (next: Preview, zip?: string) => {
    setBusy(true);
    try {
      if (zip) qc.setQueryData(siteKeys.deliveryLocation(), await browserApi().setDeliveryLocation({ zip }));
      await putPreview(next);
      await Promise.all([preorderKeys.all, cartKeys.all, searchKeys.all, productKeys.all, siteKeys.home()].map((queryKey) => qc.invalidateQueries({ queryKey })));
    } finally {
      setBusy(false);
    }
  };

  return (
    <details className={styles.preview} data-testid="preorder-preview">
      <summary>Preview pre-order city (mock BFF)</summary>
      <div className={styles.previewRow} aria-busy={busy}>
        <label htmlFor={`${id}-city`}>
          City
          <select
            id={`${id}-city`}
            value={cityIdx}
            disabled={busy}
            onChange={(e) => {
              const c = CITIES[Number(e.target.value)];
              if (c) void apply({ ...preview, place: c.place }, c.zip);
            }}
          >
            {CITIES.map((c, i) => (
              <option key={c.label} value={i}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
        <label htmlFor={`${id}-stage`}>
          Stage
          <select id={`${id}-stage`} value={preview.stage ?? ''} disabled={busy} onChange={(e) => void apply({ ...preview, stage: (e.target.value || undefined) as PreorderStage | undefined })}>
            <option value="">Live dates</option>
            <option value="UPCOMING">Upcoming</option>
            <option value="OPEN">Open</option>
            <option value="CLOSED">Closed</option>
          </select>
        </label>
        <label htmlFor={`${id}-on`}>
          Pre-order
          <select id={`${id}-on`} value={preview.enabled === false ? 'off' : 'on'} disabled={busy} onChange={(e) => void apply({ ...preview, enabled: e.target.value === 'on' })}>
            <option value="on">On</option>
            <option value="off">Off</option>
          </select>
        </label>
        <label htmlFor={`${id}-fallback`}>
          Fallback when off
          <select id={`${id}-fallback`} value={preview.fallback ?? 'SHIP'} disabled={busy} onChange={(e) => void apply({ ...preview, fallback: e.target.value as PreorderFallback })}>
            <option value="SHIP">Ship by UPS Ground</option>
            <option value="WAITLIST">Waitlist only</option>
          </select>
        </label>
      </div>
    </details>
  );
}
