'use client';

import { Sparkle } from '@afrotransact/ui';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { LolaSheet } from './LolaSheet';

/** "Ask Lola": a button in the bottom corner of Seller Center that opens the assistant. */
export function LolaLauncher() {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="fixed right-4 bottom-[calc(1rem+var(--at-bottom-bar-h,0px))] z-40 inline-flex items-center gap-2 rounded-pill border-0 bg-primary px-4 py-3 text-sm font-bold text-on-primary shadow-lg hover:bg-primary-hover"
        aria-haspopup="dialog"
      >
        <Sparkle size={18} weight="fill" aria-hidden /> {t('sc.lola.launch')}
      </button>
      <LolaSheet open={open} onOpenChange={setOpen} />
    </>
  );
}
