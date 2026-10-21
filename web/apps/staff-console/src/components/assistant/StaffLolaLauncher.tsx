'use client';

import { Sparkle } from '@afrotransact/ui';
import { useState } from 'react';

import { useT } from '@/i18n/t';

import { StaffLolaSheet } from './StaffLolaSheet';

/** "Ask Lola": a button in the bottom corner of the staff console that opens the read-only assistant. */
export function StaffLolaLauncher() {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="fixed bottom-4 right-4 z-40 inline-flex items-center gap-2 rounded-pill border-0 bg-primary px-4 py-3 text-sm font-bold text-on-primary shadow-lg hover:bg-primary-hover"
        aria-haspopup="dialog"
      >
        <Sparkle size={18} weight="fill" aria-hidden /> {t('st.lola.launch')}
      </button>
      <StaffLolaSheet open={open} onOpenChange={setOpen} />
    </>
  );
}
