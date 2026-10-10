'use client';

import { useSearchParams } from 'next/navigation';
import { useEffect } from 'react';

import { captureAttribution } from '@/lib/preorder/attribution';

/** Records ?src=&cr=&po= from advertorial links on any storefront page (last touch wins). Renders nothing. */
export function AttributionCapture() {
  const params = useSearchParams();
  const src = params.get('src');
  const cr = params.get('cr');
  const po = params.get('po');
  useEffect(() => {
    if (!src) return;
    const q = new URLSearchParams({ src });
    if (cr) q.set('cr', cr);
    if (po) q.set('po', po);
    captureAttribution(q);
  }, [src, cr, po]);
  return null;
}
