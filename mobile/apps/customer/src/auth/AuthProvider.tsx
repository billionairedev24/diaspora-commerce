/* S163 session & device security: OIDC sign-in, first-run setup (Face ID → passcode → notifications), lock on reopen,
   five wrong passcodes force a full sign-in, sign-out removes Face ID and the passcode from the device. */
import * as Crypto from 'expo-crypto';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, Keyboard } from 'react-native';

import { type TokenSource } from '@/api/http';
import { env } from '@/config/env';
import { decodeJwt, initialsOf, isExpiring } from '@/domain/jwt';
import { phaseOnLaunch, type SessionPhase, shouldLockOnResume } from '@/domain/lockPolicy';
import { type SecondFactor, type SignInStep } from '@/domain/nativeSignIn';
import { digestsEqual, passcodeDigestInput, recordUnlockAttempt, type UnlockResult } from '@/domain/passcode';
import { flashCodeFor, type SessionFlashCode } from '@/domain/sessionFlash';
import { SessionGuard } from '@/domain/sessionGuard';
import { type SessionEndReason, type SessionWarning } from '@/domain/sessionPolicy';

import { authenticateBiometric, biometricLabel, biometricsAvailable } from './biometrics';
import { beginNativeSignIn, type NativeAttempt } from './nativeAuth';
import { refreshTokens, revokeTokens, SessionGone, type SignInIntent, signInWithIdentity, type TokenSet } from './oidc';
import { secureStorage } from './secureStorage';

const K = { tokens: 'at.customer.tokens', passcode: 'at.customer.passcode', faceId: 'at.customer.faceId', setup: 'at.customer.setupComplete', attempts: 'at.customer.passcodeAttempts' };

export type SetupStep = 'face-id' | 'passcode' | 'notifications';

/** Why the person is back on sign-in; the screen words it in the shopper's language (cu.flash.*). `detail` is a dev-only hint. */
export interface SignOutFlash {
  code: 'passcode-locked' | 'passcode-reset' | 'session-ended' | 'all-devices' | SessionFlashCode;
  detail?: string;
}

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
  /** Reason shown on the sign-in screen (e.g. after five wrong passcodes); null for a plain sign-out. */
  flash: SignOutFlash | null;
  tokenSource: TokenSource;
  /** Hosted identity page (browser sheet): social providers, passkeys and creating an account. */
  signIn(intent: SignInIntent): Promise<void>;
  /** In-app form: email + password. Resolves a second-factor step when the account needs one, else signs in. */
  signInWithPassword(email: string, password: string): Promise<SignInStep>;
  sendSignInCode(): Promise<string>;
  verifySecondFactor(factor: SecondFactor, code: string, trustDevice: boolean): Promise<SignInStep>;
  /** Drops a half-finished sign-in (back from the code screen). */
  cancelSignIn(): void;
  setFaceId(enabled: boolean): Promise<boolean>;
  savePasscode(pin: string): Promise<void>;
  finishSetup(): Promise<void>;
  unlockWithPasscode(pin: string): Promise<UnlockResult>;
  unlockWithBiometrics(): Promise<boolean>;
  verifyPasscode(pin: string): Promise<boolean>;
  lock(): void;
  signOut(opts?: { flash?: SignOutFlash }): Promise<void>;
  /** Face ID (or the app passcode when Face ID is off) for payments over $100 and account changes. */
  confirmWithDevice(reason: string): Promise<boolean>;
  /** Registers work to do before sign-out (e.g. unregister the push token); returns an unsubscribe. */
  onSignOut(hook: () => Promise<void>): () => void;
  /** Real foreground interaction (touch, keyboard): feeds the inactivity timer. Never call it from timers or background work. */
  noteActivity(): void;
  /** Set while the idle limit is close and a warning is configured (warnSeconds > 0); null otherwise. */
  sessionWarning: SessionWarning | null;
  /** "Stay signed in" on the warning: counts as activity. */
  staySignedIn(): void;
  /** The API provider hands over the call that reads GET /v1/session/policy (sent as background, never activity). */
  registerPolicyFetcher(fetcher: () => Promise<unknown>): () => void;
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
  const [flash, setFlash] = useState<SignOutFlash | null>(null);
  const [passcodePrompt, setPasscodePrompt] = useState<AuthContextValue['passcodePrompt']>(null);
  const tokens = useRef<TokenSet | null>(null);
  const refreshing = useRef<Promise<string | null> | null>(null);
  const attempt = useRef<NativeAttempt | null>(null);
  const goneReason = useRef('');
  const ending = useRef(false);
  const hooks = useRef(new Set<() => Promise<void>>());
  const policyFetcher = useRef<(() => Promise<unknown>) | null>(null);
  const endBySession = useRef<(reason: SessionEndReason) => void>(() => undefined);
  const bootEnd = useRef<SessionEndReason | null>(null);
  const readyRef = useRef(false);
  const [sessionWarning, setSessionWarning] = useState<SessionWarning | null>(null);
  const [foreground, setForeground] = useState(AppState.currentState !== 'background');
  /* Sign-out after inactivity / session length (domain/sessionPolicy.ts): the clock and the cached policy live in the
     secure store; ending the session goes through signOut below, like every other way out. */
  const guard = useMemo(() => new SessionGuard({
    audience: 'customer-app',
    store: secureStorage,
    fetchPolicy: () => (policyFetcher.current ? policyFetcher.current() : Promise.reject(new Error('API not ready'))),
    onExpire: (reason) => endBySession.current(reason)
  }), []);
  const backgroundedAt = useRef<number | null>(null);
  const phaseRef = useRef(phase);
  phaseRef.current = phase;

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
    await guard.end();
    tokens.current = null;
    setSessionWarning(null);
    setUser(null);
    setFaceIdState(false);
  }, [guard]);

  const signOut = useCallback(async (opts?: { flash?: SignOutFlash }) => {
    for (const h of hooks.current) {
      try { await h(); } catch { /* best effort (offline) */ }
    }
    if (tokens.current) await revokeTokens(tokens.current);
    await clearDevice();
    setFlash(opts?.flash ?? null);
    setPhase('signed-out');
  }, [clearDevice]);

  /* Boot: restore tokens and device security from the secure store. A session that has already run past its idle or
     session-length limit (the phone sat in a drawer) is signed out here, before the lock screen is ever shown. */
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
      await guard.load(!!tokens.current);
      if (!alive) return;
      readyRef.current = true;
      const ended = bootEnd.current;
      if (ended && tokens.current) {
        ending.current = true;
        await signOut({ flash: { code: flashCodeFor(ended) } });
        if (alive) setReady(true);
        return;
      }
      setFaceIdState(face === '1');
      const p = phaseOnLaunch({ signedIn: !!tokens.current, hasPasscode: !!passcode, setupComplete: setup === '1' });
      setPhase(p);
      if (p === 'setup') setSetupStep(passcode ? 'notifications' : 'face-id');
      setReady(true);
    })();
    return () => { alive = false; };
  }, [guard, signOut]);

  endBySession.current = (reason) => {
    if (!readyRef.current) {
      bootEnd.current = reason; // cold start: the boot code signs out before showing anything
      return;
    }
    if (ending.current || !tokens.current) return;
    ending.current = true;
    void signOut({ flash: { code: flashCodeFor(reason) } });
  };

  const persistTokens = useCallback(async (t: TokenSet) => {
    tokens.current = t;
    await secureStorage.set(K.tokens, JSON.stringify(t));
  }, []);

  /* null = the session is gone (the refresh token is no longer accepted); a thrown error = could not tell (offline,
     identity restarting): the session is kept and the next request tries again. */
  const refreshAccessToken = useCallback(async (): Promise<string | null> => {
    if (!tokens.current) return null;
    refreshing.current ??= (async () => {
      try {
        const t = await refreshTokens(tokens.current as TokenSet);
        await persistTokens(t);
        return t.accessToken;
      } catch (e) {
        if (e instanceof SessionGone) {
          goneReason.current = e.reason;
          return null;
        }
        throw e;
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
      if (!isExpiring(t.expiresAt, Date.now())) return t.accessToken;
      try {
        return await refreshAccessToken();
      } catch {
        return t.accessToken; // could not reach identity: let the request try, the 401 path decides
      }
    },
    refreshAccessToken,
    onSessionEnded: (reason) => endBySession.current(reason),
    onUserRequest: () => { if (phaseRef.current === 'unlocked') guard.touch(); },
    onSessionExpired: () => {
      // Several requests fail together: the first one ends the session, the rest must not overwrite its reason.
      if (ending.current || !tokens.current) return;
      ending.current = true;
      void signOut({ flash: { code: 'session-ended', ...(env.appEnv !== 'prod' && goneReason.current ? { detail: goneReason.current } : {}) } });
    }
  }), [refreshAccessToken, signOut, guard]);

  /* A new sign-in on this phone starts the first-time setup (Face ID → passcode → notifications) unless it was done. */
  const startSession = useCallback(async (t: TokenSet) => {
    ending.current = false;
    goneReason.current = '';
    await persistTokens(t);
    await guard.begin(); // the session length counts from now
    void guard.refreshPolicy(true);
    setUser(userFrom(t));
    setFlash(null);
    await secureStorage.set(K.attempts, '0');
    const passcode = await secureStorage.get(K.passcode);
    const done = (await secureStorage.get(K.setup)) === '1';
    if (done && passcode) {
      setPhase('unlocked');
      return;
    }
    setSetupStep('face-id');
    setPhase('setup');
  }, [persistTokens, guard]);

  const signIn = useCallback(async (intent: SignInIntent) => {
    await startSession(await signInWithIdentity(intent));
  }, [startSession]);

  const settle = useCallback(async (step: SignInStep): Promise<SignInStep> => {
    const t = attempt.current?.tokens();
    if (step.kind === 'done' && t) {
      attempt.current = null;
      await startSession(t);
    }
    return step;
  }, [startSession]);

  const signInWithPassword = useCallback(async (email: string, password: string) => {
    attempt.current = await beginNativeSignIn();
    return settle(await attempt.current.password(email, password));
  }, [settle]);

  const sendSignInCode = useCallback(async () => {
    if (!attempt.current) throw new Error('Start again');
    return attempt.current.sendSmsCode();
  }, []);

  const verifySecondFactor = useCallback(async (factor: SecondFactor, code: string, trustDevice: boolean) => {
    if (!attempt.current) throw new Error('Start again');
    return settle(await attempt.current.verify(factor, code, trustDevice));
  }, [settle]);

  const cancelSignIn = useCallback(() => { attempt.current = null; }, []);

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
    if (r.kind === 'unlocked') {
      guard.touch(); // unlocking is real use
      setPhase('unlocked');
    }
    if (r.kind === 'locked-out') await signOut({ flash: { code: 'passcode-locked' } });
    return r;
  }, [signOut, verifyPasscode, guard]);

  const unlockWithBiometrics = useCallback(async () => {
    if (!faceId) return false;
    const ok = await authenticateBiometric('Unlock AfroTransact');
    if (ok) {
      await secureStorage.set(K.attempts, '0');
      guard.touch();
      setPhase('unlocked');
    }
    return ok;
  }, [faceId, guard]);

  const confirmWithDevice = useCallback(async (reason: string) => {
    if (faceId && (await authenticateBiometric(reason))) return true;
    return new Promise<boolean>((resolve) => setPasscodePrompt({ reason, resolve: (ok) => { setPasscodePrompt(null); resolve(ok); } }));
  }, [faceId]);

  const noteActivity = useCallback(() => {
    if (phaseRef.current === 'unlocked' || phaseRef.current === 'setup') guard.touch();
  }, [guard]);

  const staySignedIn = useCallback(() => {
    guard.stay();
    setSessionWarning(null);
  }, [guard]);

  const registerPolicyFetcher = useCallback((fetcher: () => Promise<unknown>) => {
    policyFetcher.current = fetcher;
    return () => { if (policyFetcher.current === fetcher) policyFetcher.current = null; };
  }, []);

  /* Soft keyboard opening is interaction that a touch handler at the root does not see. */
  useEffect(() => {
    const sub = Keyboard.addListener('keyboardDidShow', noteActivity);
    return () => sub.remove();
  }, [noteActivity]);

  /* On every resume: judge the session first (time in the background is idle time), then maybe re-read the policy
     (at most hourly). Going to the background writes the clock. Neither counts as activity. */
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      setForeground(s === 'active');
      if (s === 'background') void guard.flush();
      if (s === 'active' && tokens.current) {
        guard.check();
        void guard.refreshPolicy();
      }
    });
    return () => sub.remove();
  }, [guard]);

  /* While foregrounded: look again when the next limit (or the warning window) is due, never later than a minute. */
  const watching = ready && phase !== 'signed-out' && foreground;
  useEffect(() => {
    if (!watching) {
      setSessionWarning(null);
      return;
    }
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      const c = guard.check();
      setSessionWarning((prev) => (c?.warning ? { secondsLeft: c.warning.secondsLeft } : prev === null ? prev : null));
      if (c?.status === 'active') timer = setTimeout(tick, c.recheckInMs);
    };
    tick();
    return () => clearTimeout(timer);
  }, [watching, guard]);

  const onSignOut = useCallback((hook: () => Promise<void>) => {
    hooks.current.add(hook);
    return () => { hooks.current.delete(hook); };
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    ready, phase, setupStep, user, faceId, biometryLabel, flash, tokenSource, passcodePrompt, sessionWarning, noteActivity, staySignedIn, registerPolicyFetcher,
    signIn, signInWithPassword, sendSignInCode, verifySecondFactor, cancelSignIn, setFaceId, savePasscode, finishSetup, unlockWithPasscode, unlockWithBiometrics, verifyPasscode,
    lock: () => setPhase((p) => (p === 'unlocked' ? 'locked' : p)),
    signOut, confirmWithDevice, onSignOut
  }), [ready, phase, setupStep, user, faceId, biometryLabel, flash, tokenSource, passcodePrompt, sessionWarning, noteActivity, staySignedIn, registerPolicyFetcher, signIn, signInWithPassword, sendSignInCode, verifySecondFactor, cancelSignIn, setFaceId, savePasscode, finishSetup, unlockWithPasscode, unlockWithBiometrics, verifyPasscode, signOut, confirmWithDevice, onSignOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
