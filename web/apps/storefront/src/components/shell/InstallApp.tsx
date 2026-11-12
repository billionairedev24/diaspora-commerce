'use client';

import { type BeforeInstallPromptEvent, type InstallMode,installMode } from '@afrotransact/pwa';
import { DeviceMobile } from '@afrotransact/ui';
import { useEffect, useId, useRef, useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './InstallApp.module.css';

/** Already running as the installed app (Chromium display-mode, or iOS Safari's navigator.standalone). */
function isStandalone(): boolean {
  const ios = (navigator as Navigator & { standalone?: boolean }).standalone === true;
  return ios || (typeof window.matchMedia === 'function' && window.matchMedia('(display-mode: standalone)').matches);
}

/**
 * A quiet "Install the app" entry (never a banner, never blocking). Chromium browsers hand over an install prompt that
 * is replayed from the button; Safari on iPhone and iPad cannot be prompted, so the button shows "Share > Add to Home
 * Screen". Renders nothing when the app is installed already or the browser cannot install it.
 */
export function InstallApp() {
  const t = useT();
  const [mode, setMode] = useState<InstallMode>('none');
  const [help, setHelp] = useState(false);
  const prompt = useRef<BeforeInstallPromptEvent | null>(null);
  const helpId = useId();

  useEffect(() => {
    const evaluate = () =>
      setMode(
        installMode({
          standalone: isStandalone(),
          userAgent: navigator.userAgent,
          maxTouchPoints: navigator.maxTouchPoints,
          platform: navigator.platform,
          hasPrompt: prompt.current !== null
        })
      );
    const onPrompt = (event: Event) => {
      // Keep the browser's own mini-infobar away: the person installs from the entry, when they choose to.
      event.preventDefault();
      prompt.current = event as BeforeInstallPromptEvent;
      evaluate();
    };
    const onInstalled = () => {
      prompt.current = null;
      evaluate();
    };
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    evaluate();
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  if (mode === 'none') return null;

  const onClick = async () => {
    if (mode === 'ios') {
      setHelp((open) => !open);
      return;
    }
    const event = prompt.current;
    if (!event) return;
    prompt.current = null;
    try {
      await event.prompt();
      await event.userChoice;
    } catch {
      /* the browser declined to show the prompt: nothing to do */
    }
    setMode('none');
  };

  return (
    <div className={styles.install}>
      <button type="button" className={styles.button} onClick={() => void onClick()} aria-expanded={mode === 'ios' ? help : undefined} aria-controls={mode === 'ios' ? helpId : undefined}>
        <DeviceMobile size={20} aria-hidden />
        {t('pwa.install')}
      </button>
      {mode === 'ios' && help && (
        <p id={helpId} className={styles.help}>
          {t('pwa.iosSteps')}
        </p>
      )}
    </div>
  );
}
