/* App lock for drivers: first-run setup after the first sign-in on a phone (Face ID offer, then a 6-digit passcode),
   then the app opens locked and locks again after the grace period in the background.
   The lock is a LAYER over the app: it only decides whether the lock screen is drawn on top. It never touches the sync
   engine, the outbox or location pings (those live in DriverProvider and keep running while locked), and it keeps the
   navigator mounted, so a half-finished stop, scan or signature is still there after unlocking.
   Five wrong passcodes sign the driver out through DriverProvider.signOut, which keeps the SQLite outbox on the phone.
   Signing out (any way) removes the passcode and Face ID setting from the phone. */
import * as Crypto from 'expo-crypto';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { authenticateBiometric, biometricLabel, biometricsAvailable } from '@/auth/biometrics';
import { secureStorage } from '@/auth/secureStorage';
import { coversApp, type LockPhase, phaseOnLaunch, shouldLockOnResume } from '@/domain/lockPolicy';
import { digestsEqual, passcodeDigestInput, recordUnlockAttempt, type UnlockResult } from '@/domain/passcode';
import { useT } from '@/i18n';

import { useDriver } from './DriverProvider';

const K = { passcode: 'at.driver.passcode', faceId: 'at.driver.faceId', setup: 'at.driver.setupComplete', attempts: 'at.driver.passcodeAttempts' };

export type SetupStep = 'face-id' | 'passcode';

interface StoredSecurity {
  hasPasscode: boolean;
  faceId: boolean;
  setupComplete: boolean;
}

interface LockCtx {
  /** False until the stored security settings are read and the session is known (the app shows the boot screen). */
  ready: boolean;
  phase: LockPhase;
  setupStep: SetupStep;
  faceId: boolean;
  /** This phone has biometrics set up and the build can use them. */
  biometricsAvailable: boolean;
  biometryLabel: string;
  setFaceId(enabled: boolean): Promise<boolean>;
  savePasscode(pin: string): Promise<void>;
  unlockWithPasscode(pin: string): Promise<UnlockResult>;
  unlockWithBiometrics(): Promise<boolean>;
  lock(): void;
}

const LockContext = createContext<LockCtx | null>(null);

async function hashPasscode(salt: string, pin: string): Promise<string> {
  return Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, passcodeDigestInput(salt, pin));
}

async function readStored(): Promise<StoredSecurity> {
  const [passcode, face, setup] = await Promise.all([secureStorage.get(K.passcode), secureStorage.get(K.faceId), secureStorage.get(K.setup)]);
  return { hasPasscode: !!passcode, faceId: face === '1', setupComplete: setup === '1' };
}

export function LockProvider({ children }: { children: ReactNode }) {
  const tr = useT();
  const { phase: session, denied, signOut } = useDriver();
  const [phase, setPhase] = useState<LockPhase | null>(null);
  const [setupStep, setSetupStep] = useState<SetupStep>('face-id');
  const [faceId, setFaceIdState] = useState(false);
  const [available, setAvailable] = useState(false);
  const [storedReady, setStoredReady] = useState(false);
  const [biometryLabelText, setBiometryLabel] = useState('Face ID');
  const stored = useRef<StoredSecurity | null>(null);
  const prevSession = useRef(session);
  const backgroundedAt = useRef<number | null>(null);
  const phaseRef = useRef<LockPhase | null>(phase);
  phaseRef.current = phase;

  /* Boot: what this phone remembers, and what it can do. */
  useEffect(() => {
    let alive = true;
    void (async () => {
      const [s, avail, label] = await Promise.all([readStored(), biometricsAvailable(), biometricLabel()]);
      if (!alive) return;
      stored.current = s;
      setFaceIdState(s.faceId);
      setAvailable(avail);
      setBiometryLabel(label);
      setStoredReady(true);
    })();
    return () => { alive = false; };
  }, []);

  const wipe = useCallback(async () => {
    await Promise.all(Object.values(K).map((k) => secureStorage.remove(k).catch(() => undefined)));
    stored.current = { hasPasscode: false, faceId: false, setupComplete: false };
    setFaceIdState(false);
  }, []);

  const beginSetup = useCallback(async () => {
    const avail = await biometricsAvailable();
    setAvailable(avail);
    setSetupStep(avail ? 'face-id' : 'passcode');
    setPhase('setup');
  }, []);

  /* The session decides the lock: signed out = nothing to lock (and the passcode is forgotten); a cold start with a
     session opens locked; a fresh sign-in runs the setup once, then the app is simply unlocked. */
  useEffect(() => {
    if (!storedReady || session === 'booting') return;
    const prev = prevSession.current;
    prevSession.current = session;
    const signedIn = session !== 'signed-out' && !(session === 'denied' && denied?.revoked);
    if (!signedIn) {
      // Nothing to lock, and nothing of the last driver's passcode or Face ID setting stays on the phone.
      if (phaseRef.current !== 'signed-out') {
        setPhase('signed-out');
        void wipe();
      }
      return;
    }
    if (phaseRef.current !== null && phaseRef.current !== 'signed-out') return;
    const s = stored.current ?? { hasPasscode: false, faceId: false, setupComplete: false };
    const p = phaseRef.current === null && prev === 'booting' ? phaseOnLaunch({ signedIn: true, hasPasscode: s.hasPasscode, setupComplete: s.setupComplete }) : s.hasPasscode && s.setupComplete ? 'unlocked' : 'setup';
    if (p === 'setup') void beginSetup();
    else setPhase(p);
  }, [session, denied, storedReady, wipe, beginSetup]);

  /* Lock again when the app comes back after the grace period. Only the lock screen changes. */
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'background') backgroundedAt.current = Date.now();
      if (s === 'active') {
        if (phaseRef.current === 'unlocked' && shouldLockOnResume(backgroundedAt.current, Date.now())) setPhase('locked');
        backgroundedAt.current = null;
      }
    });
    return () => sub.remove();
  }, []);

  const setFaceId = useCallback(async (enabled: boolean) => {
    if (enabled) {
      if (!(await biometricsAvailable())) return false;
      if (!(await authenticateBiometric(tr('dr.lock.turnOnPrompt', { method: biometryLabelText }), tr('dr.lock.usePasscode')))) return false;
    }
    await secureStorage.set(K.faceId, enabled ? '1' : '0');
    setFaceIdState(enabled);
    if (stored.current) stored.current.faceId = enabled;
    if (phaseRef.current === 'setup') setSetupStep('passcode');
    return true;
  }, [tr, biometryLabelText]);

  const savePasscode = useCallback(async (pin: string) => {
    const salt = Crypto.randomUUID();
    const hash = await hashPasscode(salt, pin);
    await secureStorage.set(K.passcode, JSON.stringify({ salt, hash }));
    await secureStorage.set(K.attempts, '0');
    await secureStorage.set(K.setup, '1');
    stored.current = { hasPasscode: true, faceId: stored.current?.faceId ?? false, setupComplete: true };
    setPhase('unlocked');
  }, []);

  const unlockWithPasscode = useCallback(async (pin: string): Promise<UnlockResult> => {
    const attempts = Number((await secureStorage.get(K.attempts)) ?? '0') || 0;
    let correct = false;
    const raw = await secureStorage.get(K.passcode);
    if (raw) {
      const { salt, hash } = JSON.parse(raw) as { salt: string; hash: string };
      correct = digestsEqual(await hashPasscode(salt, pin), hash);
    }
    const r = recordUnlockAttempt(attempts, correct);
    await secureStorage.set(K.attempts, String(r.attempts));
    if (r.kind === 'unlocked') setPhase('unlocked');
    // Five wrong passcodes: a full sign-out. DriverProvider.signOut keeps the outbox on the phone.
    if (r.kind === 'locked-out') await signOut({ notice: tr('dr.lock.lockedOut') });
    return r;
  }, [signOut, tr]);

  const unlockWithBiometrics = useCallback(async () => {
    if (!faceId) return false;
    const ok = await authenticateBiometric(tr('dr.lock.unlockPrompt'), tr('dr.lock.usePasscode'));
    if (ok) {
      await secureStorage.set(K.attempts, '0');
      setPhase('unlocked');
    }
    return ok;
  }, [faceId, tr]);

  const value = useMemo<LockCtx>(() => ({
    ready: storedReady && session !== 'booting' && phase !== null,
    phase: phase ?? 'signed-out',
    setupStep, faceId, biometricsAvailable: available, biometryLabel: biometryLabelText,
    setFaceId, savePasscode, unlockWithPasscode, unlockWithBiometrics,
    lock: () => setPhase((p) => (p === 'unlocked' ? 'locked' : p))
  }), [storedReady, session, phase, setupStep, faceId, available, biometryLabelText, setFaceId, savePasscode, unlockWithPasscode, unlockWithBiometrics]);

  return <LockContext.Provider value={value}>{children}</LockContext.Provider>;
}

export function useLock(): LockCtx {
  const c = useContext(LockContext);
  if (!c) throw new Error('useLock outside LockProvider');
  return c;
}

/** True while the lock layer hides the app. Safe outside the provider (then never locked): camera and scan screens
    use it to stop reading labels behind the lock screen. */
export function useAppLocked(): boolean {
  const c = useContext(LockContext);
  return c ? coversApp(c.phase) : false;
}
