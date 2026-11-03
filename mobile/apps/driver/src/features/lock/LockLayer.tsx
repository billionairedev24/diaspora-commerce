/* The lock layer: draws the setup or lock screen OVER the navigator (which stays mounted, so a stop, a scan or a
   signature in progress is exactly where it was). Never reads or changes sync state. */
import { useEffect } from 'react';
import { BackHandler } from 'react-native';

import { useDriver } from '@/session/DriverProvider';
import { useLock } from '@/session/LockProvider';

import { LockOverlay } from './LockOverlay';
import { SetupFlow } from './SetupFlow';

/** Which screen to draw, if any. Setup waits until the account is known to have driver access. */
export function useLockScreen(): 'setup' | 'locked' | null {
  const { phase } = useLock();
  const { phase: session } = useDriver();
  if (phase === 'locked') return 'locked';
  if (phase === 'setup' && (session === 'ready' || session === 'checking')) return 'setup';
  return null;
}

export function LockLayer() {
  const screen = useLockScreen();
  // The hardware back button must not reach the screens hidden behind the lock.
  useEffect(() => {
    if (!screen) return undefined;
    const sub = BackHandler.addEventListener('hardwareBackPress', () => true);
    return () => sub.remove();
  }, [screen]);
  if (screen === 'locked') return <LockOverlay />;
  if (screen === 'setup') return <SetupFlow />;
  return null;
}
