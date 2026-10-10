/* S163 session & device security: OIDC sign-in, first-run setup (Face ID → passcode → notifications), lock on reopen,
   five wrong passcodes force a full sign-in, sign-out removes Face ID and the passcode from the device. */
import * as Crypto from 'expo-crypto';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { type TokenSource } from '@/api/http';
import { decodeJwt, initialsOf, isExpiring } from '@/domain/jwt';
import { phaseOnLaunch, type SessionPhase, shouldLockOnResume } from '@/domain/lockPolicy';
import { digestsEqual, LOCKED_OUT_MESSAGE, passcodeDigestInput, recordUnlockAttempt, type UnlockResult } from '@/domain/passcode';

import { authenticateBiometric, biometricLabel, biometricsAvailable } from './biometrics';
import { refreshTokens, revokeTokens, type SignInIntent, signInWithIdentity, type TokenSet } from './oidc';
import { secureStorage } from './secureStorage';

const K = { tokens: 'at.customer.tokens', passcode: 'at.customer.passcode', faceId: 'at.customer.faceId', setup: 'at.customer.setupComplete', attempts: 'at.customer.passcodeAttempts' };

export type SetupStep = 'face-id' | 'passcode' | 'notifications';

export interface SessionUser {
  id: string;
  first: string;
  last: string;
  email: string;
  phone: string;
  initials: string;
}

interface AuthContextValue {
  ready: boolean;
  phase: SessionPhase;
  setupStep: SetupStep;
  user: SessionUser | null;
  faceId: boolean;
  biometryLabel: string;
  /** Message shown on the sign-in screen (e.g. after five wrong passcodes). */
  flash: string;
  tokenSource: TokenSource;
  signIn(intent: SignInIntent): Promise<void>;
  setFaceId(enabled: boolean): Promise<boolean>;
  savePasscode(pin: string): Promise<void>;
  finishSetup(): Promise<void>;
  unlockWithPasscode(pin: string): Promise<UnlockResult>;
  unlockWithBiometrics(): Promise<boolean>;
  verifyPasscode(pin: string): Promise<boolean>;
  lock(): void;
  signOut(opts?: { flash?: string }): Promise<void>;
  /** Face ID (or the app passcode when Face ID is off) for payments over $100 and account changes. */
  confirmWithDevice(reason: string): Promise<boolean>;
  /** Registers work to do before sign-out (e.g. unregister the push token); returns an unsubscribe. */
  onSignOut(hook: () => Promise<void>): () => void;
  /** Set by the passcode confirmation sheet. */
  passcodePrompt: { reason: string; resolve: (ok: boolean) => void } | null;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function userFrom(tokens: TokenSet): SessionUser {
  const c = decodeJwt(tokens.idToken ?? tokens.accessToken) ?? {};
  const first = c.given_name ?? '';
  const last = c.family_name ?? '';
  return { id: c.sub ?? '', first: first || 'there', last, email: c.email ?? '', phone: c.phone_number ?? '', initials: initialsOf(first, last) };
}

async function hashPasscode(salt: string, pin: string): Promise<string> {
  return Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, passcodeDigestInput(salt, pin));
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [phase, setPhase] = useState<SessionPhase>('signed-out');
  const [setupStep, setSetupStep] = useState<SetupStep>('face-id');
  const [user, setUser] = useState<SessionUser | null>(null);
  const [faceId, setFaceIdState] = useState(false);
  const [biometryLabel, setBiometryLabel] = useState('Face ID');
  const [flash, setFlash] = useState('');
  const [passcodePrompt, setPasscodePrompt] = useState<AuthContextValue['passcodePrompt']>(null);
  const tokens = useRef<TokenSet | null>(null);
  const refreshing = useRef<Promise<string | null> | null>(null);
  const hooks = useRef(new Set<() => Promise<void>>());
  const backgroundedAt = useRef<number | null>(null);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

  /* Boot: restore tokens and device security from the secure store. */
  useEffect(() => {
    let alive = true;
    void (async () => {
      const [rawTokens, passcode, face, setup] = await Promise.all([secureStorage.get(K.tokens), secureStorage.get(K.passcode), secureStorage.get(K.faceId), secureStorage.get(K.setup)]);
      setBiometryLabel(await biometricLabel());
      if (!alive) return;
      if (rawTokens) {
        try {
          const t = JSON.parse(rawTokens) as TokenSet;
          tokens.current = t;
          setUser(userFrom(t));
        } catch {
          tokens.current = null;
        }
      }
      setFaceIdState(face === '1');
      const p = phaseOnLaunch({ signedIn: !!tokens.current, hasPasscode: !!passcode, setupComplete: setup === '1' });
      setPhase(p);
      if (p === 'setup') setSetupStep(passcode ? 'notifications' : 'face-id');
      setReady(true);
    })();
    return () => { alive = false; };
  }, []);

  /* Lock again when the app comes back after the grace period. */
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

  const clearDevice = useCallback(async () => {
    await Promise.all(Object.values(K).map((k) => secureStorage.remove(k)));
    tokens.current = null;
    setUser(null);
    setFaceIdState(false);
  }, []);

  const signOut = useCallback(async (opts?: { flash?: string }) => {
    for (const h of hooks.current) {
      try { await h(); } catch { /* best effort (offline) */ }
    }
    if (tokens.current) await revokeTokens(tokens.current);
    await clearDevice();
    setFlash(opts?.flash ?? '');
    setPhase('signed-out');
  }, [clearDevice]);

  const persistTokens = useCallback(async (t: TokenSet) => {
    tokens.current = t;
    await secureStorage.set(K.tokens, JSON.stringify(t));
  }, []);

  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    if (!tokens.current) return null;
    refreshing.current ??= (async () => {
      try {
        const t = await refreshTokens(tokens.current as TokenSet);
        await persistTokens(t);
        return t.accessToken;
      } catch {
        return null;
      } finally {
        refreshing.current = null;
      }
    })();
    return refreshing.current;
  }, [persistTokens]);

  const tokenSource = useMemo<TokenSource>(() => ({
    getAccessToken: async () => {
      const t = tokens.current;
      if (!t) return null;
      if (isExpiring(t.expiresAt, Date.now())) return refreshAccessToken();
      return t.accessToken;
    },
    refreshAccessToken,
    onSessionExpired: () => { void signOut({ flash: 'Your session ended. Sign in again.' }); }
  }), [refreshAccessToken, signOut]);

  const signIn = useCallback(async (intent: SignInIntent) => {
    const t = await signInWithIdentity(intent);
    await persistTokens(t);
    setUser(userFrom(t));
    setFlash('');
    await secureStorage.set(K.attempts, '0');
    setSetupStep('face-id');
    setPhase('setup');
  }, [persistTokens]);

  const setFaceId = useCallback(async (enabled: boolean) => {
    if (enabled) {
      if (!(await biometricsAvailable())) return false;
      if (!(await authenticateBiometric(`Turn on ${biometryLabel} for AfroTransact`))) return false;
    }
    await secureStorage.set(K.faceId, enabled ? '1' : '0');
    setFaceIdState(enabled);
    if (phaseRef.current === 'setup') setSetupStep('passcode');
    return true;
  }, [biometryLabel]);

  const savePasscode = useCallback(async (pin: string) => {
    const salt = Crypto.randomUUID();
    const hash = await hashPasscode(salt, pin);
    await secureStorage.set(K.passcode, JSON.stringify({ salt, hash }));
    await secureStorage.set(K.attempts, '0');
    if (phaseRef.current === 'setup') setSetupStep('notifications');
  }, []);

  const finishSetup = useCallback(async () => {
    await secureStorage.set(K.setup, '1');
    setPhase('unlocked');
  }, []);

  const verifyPasscode = useCallback(async (pin: string) => {
    const raw = await secureStorage.get(K.passcode);
    if (!raw) return false;
    const { salt, hash } = JSON.parse(raw) as { salt: string; hash: string };
    return digestsEqual(await hashPasscode(salt, pin), hash);
  }, []);

  const unlockWithPasscode = useCallback(async (pin: string): Promise<UnlockResult> => {
    const attempts = Number((await secureStorage.get(K.attempts)) ?? '0') || 0;
    const r = recordUnlockAttempt(attempts, await verifyPasscode(pin));
    await secureStorage.set(K.attempts, String(r.attempts));
    if (r.kind === 'unlocked') setPhase('unlocked');
    if (r.kind === 'locked-out') await signOut({ flash: LOCKED_OUT_MESSAGE });
    return r;
  }, [signOut, verifyPasscode]);

  const unlockWithBiometrics = useCallback(async () => {
    if (!faceId) return false;
    const ok = await authenticateBiometric('Unlock AfroTransact');
    if (ok) {
      await secureStorage.set(K.attempts, '0');
      setPhase('unlocked');
    }
    return ok;
  }, [faceId]);

  const confirmWithDevice = useCallback(async (reason: string) => {
    if (faceId && (await authenticateBiometric(reason))) return true;
    return new Promise<boolean>((resolve) => setPasscodePrompt({ reason, resolve: (ok) => { setPasscodePrompt(null); resolve(ok); } }));
  }, [faceId]);

  const onSignOut = useCallback((hook: () => Promise<void>) => {
    hooks.current.add(hook);
    return () => { hooks.current.delete(hook); };
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    ready, phase, setupStep, user, faceId, biometryLabel, flash, tokenSource, passcodePrompt,
    signIn, setFaceId, savePasscode, finishSetup, unlockWithPasscode, unlockWithBiometrics, verifyPasscode,
    lock: () => setPhase((p) => (p === 'unlocked' ? 'locked' : p)),
    signOut, confirmWithDevice, onSignOut
  }), [ready, phase, setupStep, user, faceId, biometryLabel, flash, tokenSource, passcodePrompt, signIn, setFaceId, savePasscode, finishSetup, unlockWithPasscode, unlockWithBiometrics, verifyPasscode, signOut, confirmWithDevice, onSignOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
