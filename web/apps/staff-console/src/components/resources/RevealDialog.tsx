'use client';

import { type OneTimeSecret } from '@afrotransact/api-client';
import { Button, Dialog, TextField } from '@afrotransact/ui';
import { Copy } from '@phosphor-icons/react/ssr';
import { useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';

/**
 * Shows a secret once, right after it was created (a new OAuth client secret). It lives in this component's state only: the
 * callers keep it out of the query cache, and it is gone when the dialog closes. Copying uses the clipboard; nothing is
 * stored in the browser.
 */
export function RevealDialog({ secret, onClose }: { secret: OneTimeSecret | null; onClose: () => void }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  const close = () => {
    setCopied(false);
    onClose();
  };
  const copy = async () => {
    if (!secret) return;
    try {
      await navigator.clipboard.writeText(secret.value);
      setCopied(true);
    } catch {
      setCopied(false); // the value is selectable in the field below
    }
  };
  return (
    <Dialog open={secret !== null} onOpenChange={(o) => !o && close()} title={secret?.label ?? t('st.reveal.title')} width={520}>
      {secret ? (
        <div className="flex flex-col gap-4">
          <Notice tone="warning" title={t('st.reveal.once')}>
            {secret.note}
          </Notice>
          <TextField id="reveal-value" label={secret.label} readOnly value={secret.value} onFocus={(e) => e.currentTarget.select()} />
          <div className="flex flex-wrap justify-end gap-2">
            <Button variant="neutral" icon={<Copy size={16} aria-hidden />} onClick={() => void copy()}>
              {copied ? t('st.reveal.copied') : t('st.reveal.copy')}
            </Button>
            <Button onClick={close}>{t('st.reveal.done')}</Button>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}
