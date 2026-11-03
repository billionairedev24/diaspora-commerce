'use client';

import { Button, MaintenancePage } from '@afrotransact/ui';
import { useCallback, useEffect, useState } from 'react';

import { BFF_BASE_PATH } from '@/lib/config';

const POLL_MS = 20_000;

export interface MaintenanceScreenProps {
  title: string;
  message: string;
  /** ISO time staff gave; absent when unknown. Shown in the reader's own time zone. */
  expectedBackAt?: string;
  /** "We expect to be back around {time}." */
  expectedTemplate: string;
  /** Shown when no time is known. */
  workingLabel: string;
  checkLabel: string;
  locale: string;
}

/** The maintenance page. It asks the BFF every few seconds and reloads by itself when maintenance ends. */
export function MaintenanceScreen({ title, message, expectedBackAt, expectedTemplate, workingLabel, checkLabel, locale }: MaintenanceScreenProps) {
  const [checking, setChecking] = useState(false);
  // Formatted after mount: the server's time zone is not the reader's.
  const [expected, setExpected] = useState(expectedBackAt ? '' : workingLabel);
  useEffect(() => {
    if (!expectedBackAt) return;
    const time = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(new Date(expectedBackAt));
    setExpected(expectedTemplate.replace('{time}', time));
  }, [expectedBackAt, expectedTemplate, locale]);
  const check = useCallback(async () => {
    setChecking(true);
    try {
      const res = await fetch(`${BFF_BASE_PATH}/v1/maintenance`, { cache: 'no-store' });
      if (res.ok && !((await res.json()) as { active: boolean }).active) window.location.reload();
    } catch {
      /* still unreachable: keep the page up */
    } finally {
      setChecking(false);
    }
  }, []);
  useEffect(() => {
    const id = window.setInterval(() => void check(), POLL_MS);
    return () => window.clearInterval(id);
  }, [check]);
  return (
    <MaintenancePage title={title} message={message} expected={expected}>
      <Button variant="secondary" onClick={() => void check()} disabled={checking}>
        {checkLabel}
      </Button>
    </MaintenancePage>
  );
}
