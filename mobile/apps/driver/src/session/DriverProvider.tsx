/* Session + sync wiring for the driver app.
   S081: OIDC sign-in, then GET /v1/driver/me decides access (No driver access / revoked). Revocation — from /me, from
   `signOut: true` on a sync, or on the periodic re-check — signs the driver out after a last best-effort sync, and the
   outbox stays on the phone (events captured before revokedAt are still accepted later).
   S087: the SyncEngine queues every action in SQLite and syncs in order when online.
   S255: while on shift and in the foreground, location pings (throttled) feed nearest-driver pickup offers; the
   newest fix also stamps queued events.
   Sign-out policy (domain/sessionPolicy.ts): idle 14 h and session length 14 h by default, judged on the device from the
   driver's own touches. Location pings, the sync engine and push registration are background work and never count as
   activity, and this feature never stops them; when a limit passes the session ends through signOut below (a last
   best-effort sync, outbox kept). */
import * as Crypto from 'expo-crypto';
import * as Device from 'expo-device';
import * as Network from 'expo-network';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AppState, Keyboard, Platform } from 'react-native';

import { createDriverApi, type DriverApi } from '@/api/client';
import { type TokenSource } from '@/api/http';
import { beginNativeSignIn, type NativeAttempt } from '@/auth/nativeAuth';
import { refreshTokens, revokeTokens, SessionGone, type TokenSet } from '@/auth/oidc';
import { secureStorage } from '@/auth/secureStorage';
import { env } from '@/config/env';
import { decideAccess } from '@/domain/access';
import { decodeJwt, isExpiring } from '@/domain/jwt';
import { type EnrolMethod, type SecondFactor, type SignInStep, type TotpSetup } from '@/domain/nativeSignIn';
import { SessionGuard } from '@/domain/sessionGuard';
import { type SessionEndReason, type SessionWarning } from '@/domain/sessionPolicy';
import { onShift } from '@/domain/shift';
import { useT } from '@/i18n';
import { currentFix, ensureLocationPermission, watchForegroundFixes } from '@/lib/location';
import { capabilities } from '@/platform/runtime';
import { driverStore } from '@/storage';
import { type EngineState, SyncEngine } from '@/sync/engine';
import { LocationPinger } from '@/sync/locationPinger';

import { endSession, type EndSessionParts, forgetSession } from './endSession';
import { loadNotifications } from './notificationsModule';

const TOKENS = 'at.driver.tokens';
const PAUSED = 'sync.paused';
const ACCESS_RECHECK_MS = 5 * 60_000;

export interface DriverUser {
  sub: string;
  name: string;
  email: string;
  perms: string[];
}

export type Phase = 'booting' | 'signed-out' | 'checking' | 'denied' | 'ready';

interface Ctx {
  phase: Phase;
  user: DriverUser | null;
  denied: { message: string; grantHint: string; revoked: boolean } | null;
  engine: SyncEngine;
  sync: EngineState;
  api: DriverApi;
  /** Manual "no signal" switch (Profile) on top of the real network state. */
  paused: boolean;
  setPaused(p: boolean): void;
  /** In-app form: email + password; resolves a second-factor step when the account needs one. */
  signInWithPassword(email: string, password: string): Promise<SignInStep>;
  sendSignInCode(): Promise<string>;
  /** "Email me a code": emails a sign-in code to the verified account email (throttled). */
  sendEmailCode(): Promise<string>;
  /** Forced enrolment after the password (mandatory two-step verification, nothing set up): no code was sent. */
  enrolTotp(): Promise<TotpSetup>;
  enrolEmail(): Promise<string>;
  /** Confirms the first code. Resolves the recovery codes to show once; the session starts after finishEnrolment(). */
  confirmEnrol(method: EnrolMethod, code: string): Promise<string[]>;
  finishEnrolment(): Promise<void>;
  verifySecondFactor(factor: SecondFactor, code: string, trustDevice: boolean): Promise<SignInStep>;
  cancelSignIn(): void;
  /** Set when the session ended on its own (the refresh token was refused). */
  notice: string;
  /** Ends the session (after a last sync). The outbox stays on the phone. `notice` is shown on the sign-in screen. */
  signOut(opts?: { notice?: string }): Promise<void>;
  recheckAccess(): Promise<void>;
  /** Real foreground interaction (a touch, the keyboard opening): feeds the inactivity timer. Never call it from sync, pings or timers. */
  noteActivity(): void;
  /** Set while the idle limit is close and a warning is configured (warnSeconds > 0); null otherwise. */
  sessionWarning: SessionWarning | null;
  /** "Stay signed in" on the warning: counts as activity. */
  staySignedIn(): void;
}

const DriverContext = createContext<Ctx | null>(null);

function userOf(t: TokenSet): DriverUser {
  const c = decodeJwt(t.idToken ?? t.accessToken) ?? {};
  return { sub: c.sub ?? '', name: `${c.given_name ?? ''} ${c.family_name ?? ''}`.trim() || (c.email ?? 'Driver'), email: c.email ?? '', perms: c.perms ?? [] };
}

export function DriverProvider({ children }: { children: ReactNode }) {
  const tokens = useRef<TokenSet | null>(null);
  const refreshing = useRef<Promise<string | null> | null>(null);
  const pushToken = useRef<string | null>(null);
  const attempt = useRef<NativeAttempt | null>(null);
  const expire = useRef<() => void>(() => undefined);
  const goneReason = useRef('');
  const tr = useT();
  const trRef = useRef(tr);
  trRef.current = tr;
  /** A sign-out is under way: a 401 from the last sync must not overwrite its message. */
  const ending = useRef(false);
  const bootDone = useRef(false);
  const bootEnd = useRef<SessionEndReason | null>(null);
  const endBySession = useRef<(reason: SessionEndReason) => void>(() => undefined);
  const apiRef = useRef<DriverApi | null>(null);
  const [sessionWarning, setSessionWarning] = useState<SessionWarning | null>(null);
  const [notice, setNotice] = useState('');
  const [phase, setPhase] = useState<Phase>('booting');
  const [user, setUser] = useState<DriverUser | null>(null);
  const [denied, setDenied] = useState<Ctx['denied']>(null);
  const [paused, setPausedState] = useState(false);
  const [netUp, setNetUp] = useState(true);
  const [appActive, setAppActive] = useState(AppState.currentState !== 'background');

  /* The sign-out clock and the cached policy (secure store). The policy is read through the typed client, as a
     background request. */
  const guard = useMemo(() => new SessionGuard({
    audience: 'driver',
    store: secureStorage,
    fetchPolicy: () => (apiRef.current ? apiRef.current.sessionPolicy() : Promise.reject(new Error('API not ready'))),
    onExpire: (reason) => endBySession.current(reason)
  }), []);

  const tokenSource = useMemo<TokenSource>(() => {
    const refreshAccessToken = async (): Promise<string | null> => {
      if (!tokens.current) return null;
      refreshing.current ??= refreshTokens(tokens.current)
        .then(async (t) => { tokens.current = t; await secureStorage.set(TOKENS, JSON.stringify(t)); return t.accessToken; })
        .catch((e: unknown) => { if (e instanceof SessionGone) { goneReason.current = e.reason; return null; } throw e; })
        .finally(() => { refreshing.current = null; });
      return refreshing.current;
    };
    return {
      refreshAccessToken,
      onSessionExpired: () => expire.current(),
      onSessionEnded: (reason) => endBySession.current(reason),
      getAccessToken: async () => {
        const t = tokens.current;
        if (!t) return null;
        if (!isExpiring(t.expiresAt, Date.now())) return t.accessToken;
        try {
          return await refreshAccessToken();
        } catch {
          return t.accessToken; // identity unreachable: let the request try
        }
      }
    };
  }, []);

  const api = useMemo(() => createDriverApi(tokenSource), [tokenSource]);
  apiRef.current = api;
  const engineRef = useRef<SyncEngine | null>(null);
  const pinger = useMemo(() => new LocationPinger({
    watch: watchForegroundFixes,
    send: (fix) => api.location(engineRef.current?.getState().outbox.deviceId ?? '', fix),
    now: () => Date.now()
  }), [api]);
  /* Events carry GPS: the pinger's fresh fix when on shift, otherwise a one-off fix (null without permission). */
  const position = useCallback(async () => pinger.latest() ?? (await currentFix()), [pinger]);
  const engine = useMemo(() => new SyncEngine({ store: driverStore, api, uuid: () => Crypto.randomUUID(), now: () => new Date(), position }), [api, position]);
  engineRef.current = engine;
  const sync = useSyncExternalStore(engine.subscribe, engine.getState, engine.getState);

  /* Everything that ends a session goes through ./endSession: credentials and the sign-out clock are forgotten, the
     outbox is not. */
  const endParts = useMemo<EndSessionParts>(() => ({
    engine,
    unregisterPush: async () => {
      const t = pushToken.current;
      pushToken.current = null;
      if (t) await api.unregisterDevice(t);
    },
    revokeTokens: async () => { if (tokens.current) await revokeTokens(tokens.current); },
    removeTokens: async () => {
      tokens.current = null;
      await secureStorage.remove(TOKENS);
    },
    forgetClock: () => guard.end()
  }), [api, engine, guard]);
  const clearSession = useCallback(() => forgetSession(endParts), [endParts]);

  const registerPush = useCallback(async () => {
    // Remote push needs our own build: Expo Go (SDK 53+) has no push tokens for our app (capabilities.remotePush).
    if (!capabilities.remotePush || !Device.isDevice) return;
    try {
      const Notifications = await loadNotifications();
      if (!Notifications) return;
      const perm = await Notifications.getPermissionsAsync();
      if (!(perm.granted || (await Notifications.requestPermissionsAsync()).granted)) return;
      const t = String((await Notifications.getDevicePushTokenAsync()).data);
      pushToken.current = t;
      await api.registerDevice({ platform: Platform.OS === 'ios' ? 'ios' : 'android', token: t, provider: Platform.OS === 'ios' ? 'apns' : 'fcm', ...(Device.deviceName ? { deviceName: Device.deviceName } : {}) });
    } catch {
      /* retried on next start */
    }
  }, [api]);

  const revoke = useCallback(async (message: string, grantHint: string) => {
    await engine.flush().catch(() => undefined); // last sync with the current token: events before revokedAt count
    await clearSession();
    setDenied({ message, grantHint, revoked: true });
    setPhase('denied');
  }, [clearSession, engine]);

  const checkAccess = useCallback(async (u: DriverUser) => {
    setPhase((p) => (p === 'ready' ? p : 'checking'));
    try {
      const r = await api.me();
      const d = decideAccess(r.status, r.body, u.name);
      if (d.kind === 'ok') {
        setDenied(null);
        setPhase('ready');
        void engine.refreshRoute().catch(() => undefined);
        void registerPush();
        return;
      }
      if (d.kind === 'revoked') return revoke(d.message, d.grantHint);
      if (d.kind === 'denied') {
        void engine.flush(); // a queued outbox may still be accepted with this token
        setDenied({ message: d.message, grantHint: d.grantHint, revoked: false });
        setPhase('denied');
        return;
      }
      setPhase('ready');
    } catch {
      // Offline: keep working from the cached route; access is re-checked when back online.
      setPhase('ready');
    }
  }, [api, engine, registerPush, revoke]);

  /* Boot: restore session, outbox and cached route. */
  useEffect(() => {
    void (async () => {
      await engine.init();
      const p = await driverStore.getKv<boolean>(PAUSED);
      setPausedState(!!p);
      const raw = await secureStorage.get(TOKENS);
      if (!raw) {
        await guard.load(false);
        bootDone.current = true;
        return setPhase('signed-out');
      }
      tokens.current = JSON.parse(raw) as TokenSet;
      const u = userOf(tokens.current);
      setUser(u);
      engine.setUser(u.sub);
      await guard.load(true); // a limit that passed while the app was closed is found here, before anything is shown
      bootDone.current = true;
      if (bootEnd.current) {
        endBySession.current(bootEnd.current); // last best-effort sync, outbox kept, then the sign-in screen with the reason
        return;
      }
      await checkAccess(u);
    })();
  }, [engine, checkAccess, guard]);

  /* Network state → engine. */
  useEffect(() => {
    void Network.getNetworkStateAsync().then((s) => setNetUp(!!s.isConnected && s.isInternetReachable !== false)).catch(() => undefined);
    const sub = Network.addNetworkStateListener((s) => setNetUp(!!s.isConnected && s.isInternetReachable !== false));
    return () => sub.remove();
  }, []);
  useEffect(() => { engine.setOnline(netUp && !paused); }, [engine, netUp, paused]);

  /* Location pings: foreground only, on shift, signed in with access. Offline pings are skipped, not queued. */
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => setAppActive(s === 'active'));
    return () => sub.remove();
  }, []);
  const shiftOn = phase === 'ready' && !!sync.route && onShift(sync.route);
  useEffect(() => { pinger.setOnline(netUp && !paused); }, [pinger, netUp, paused]);
  useEffect(() => {
    if (shiftOn && appActive) void pinger.start();
    else pinger.stop();
  }, [pinger, shiftOn, appActive]);
  useEffect(() => () => pinger.stop(), [pinger]);

  /* Revocation reported by a sync (signOut: true). */
  useEffect(() => {
    if (sync.revokedAt && phase === 'ready') void revoke('Your driver access was just removed by an administrator. Unsynced events were kept on this device.', 'A developer or super admin can grant delivery:operate in Backoffice → Access control.');
  }, [sync.revokedAt, phase, revoke]);

  /* Re-check access on resume and every 5 minutes (the SSE stream is not consumed yet). */
  useEffect(() => {
    if (phase !== 'ready' || !user) return;
    const id = setInterval(() => void checkAccess(user), ACCESS_RECHECK_MS);
    const sub = AppState.addEventListener('change', (s) => { if (s === 'active') { void checkAccess(user); void engine.flush(); } });
    return () => { clearInterval(id); sub.remove(); };
  }, [phase, user, checkAccess, engine]);

  const startSession = useCallback(async (t: TokenSet) => {
    tokens.current = t;
    await secureStorage.set(TOKENS, JSON.stringify(t));
    ending.current = false;
    await guard.begin(); // the session length counts from this sign-in
    void guard.refreshPolicy(true);
    setNotice('');
    const u = userOf(t);
    setUser(u);
    engine.setUser(u.sub);
    void ensureLocationPermission();
    await checkAccess(u);
    if (engine.getState().route) void engine.record({ type: 'session.signed_in' }).catch(() => undefined);
  }, [engine, checkAccess, guard]);

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

  const sendEmailCode = useCallback(async () => {
    if (!attempt.current) throw new Error('Start again');
    return attempt.current.sendEmailCode();
  }, []);

  const enrolTotp = useCallback(async () => {
    if (!attempt.current) throw new Error('Start again');
    return attempt.current.enrolTotp();
  }, []);

  const enrolEmail = useCallback(async () => {
    if (!attempt.current) throw new Error('Start again');
    return attempt.current.enrolEmail();
  }, []);

  /** The first factor is confirmed: the recovery codes are shown once before the session starts. */
  const enrolled = useRef<SignInStep | null>(null);
  const confirmEnrol = useCallback(async (method: EnrolMethod, code: string) => {
    if (!attempt.current) throw new Error('Start again');
    const step = await attempt.current.confirmEnrol(method, code);
    if (step.kind !== 'done') return [];
    if (step.recoveryCodes.length === 0) {
      await settle(step);
      return [];
    }
    enrolled.current = step;
    return step.recoveryCodes;
  }, [settle]);

  const finishEnrolment = useCallback(async () => {
    const step = enrolled.current;
    enrolled.current = null;
    if (step) await settle(step);
  }, [settle]);

  const verifySecondFactor = useCallback(async (factor: SecondFactor, code: string, trustDevice: boolean) => {
    if (!attempt.current) throw new Error('Start again');
    return settle(await attempt.current.verify(factor, code, trustDevice));
  }, [settle]);

  const cancelSignIn = useCallback(() => { attempt.current = null; }, []);

  const signOut = useCallback(async (opts?: { notice?: string }) => {
    ending.current = true;
    await endSession(endParts); // last best-effort sync, then credentials go; the outbox stays
    setUser(null);
    setDenied(null);
    setSessionWarning(null);
    setNotice(opts?.notice ?? '');
    setPhase('signed-out');
  }, [endParts]);

  /* The sign-out policy ended the session (the device clock, or a 401 SESSION_* from the BFF): the same sign-out as
     every other way out, with the reason in the driver's language on the sign-in screen. Mid-route nothing is lost:
     signOut syncs once more first and keeps whatever it could not send. */
  endBySession.current = (reason) => {
    if (!bootDone.current) {
      bootEnd.current = reason;
      return;
    }
    if (ending.current || !tokens.current) return;
    void signOut({ notice: trRef.current(reason === 'idle' ? 'dr.session.idle' : 'dr.session.absolute') });
  };

  expire.current = () => {
    if (ending.current || phase === 'signed-out' || !tokens.current) return; // the first failure ends the session; the rest must not repeat it
    void (async () => {
      await clearSession();
      setUser(null);
      setDenied(null);
      setNotice(`Your session ended. Sign in again.${env.appEnv !== 'prod' && goneReason.current ? ` (${goneReason.current})` : ''}`);
      setPhase('signed-out');
    })();
  };

  const noteActivity = useCallback(() => guard.touch(), [guard]);
  const staySignedIn = useCallback(() => {
    guard.stay();
    setSessionWarning(null);
  }, [guard]);

  /* The soft keyboard opening is interaction a touch handler at the root does not see. */
  useEffect(() => {
    const sub = Keyboard.addListener('keyboardDidShow', noteActivity);
    return () => sub.remove();
  }, [noteActivity]);

  /* Resume: judge the session first (time in the background is idle time), then maybe re-read the policy (hourly at
     most). Going to the background writes the clock. Neither is activity. Pings and sync are not touched. */
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'background') void guard.flush();
      if (s === 'active' && tokens.current) {
        guard.check();
        void guard.refreshPolicy();
      }
    });
    return () => sub.remove();
  }, [guard]);

  /* While foregrounded, look again when the next limit (or the warning window) is due, never later than a minute. */
  const watching = phase !== 'booting' && phase !== 'signed-out' && appActive;
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

  const value = useMemo<Ctx>(() => ({
    phase, user, denied, engine, sync, api, paused, sessionWarning, noteActivity, staySignedIn,
    setPaused: (p) => { setPausedState(p); void driverStore.setKv(PAUSED, p); },
    signInWithPassword, sendSignInCode, sendEmailCode, enrolTotp, enrolEmail, confirmEnrol, finishEnrolment, verifySecondFactor, cancelSignIn, notice, signOut,
    recheckAccess: async () => { if (user) await checkAccess(user); }
  }), [phase, user, denied, engine, sync, api, paused, sessionWarning, noteActivity, staySignedIn, signInWithPassword, sendSignInCode, sendEmailCode, enrolTotp, enrolEmail, confirmEnrol, finishEnrolment, verifySecondFactor, cancelSignIn, notice, signOut, checkAccess]);

  return <DriverContext.Provider value={value}>{children}</DriverContext.Provider>;
}

export function useDriver(): Ctx {
  const c = useContext(DriverContext);
  if (!c) throw new Error('useDriver outside DriverProvider');
  return c;
}

/** The current route (cached, with local events applied). */
export function useRoute() {
  return useDriver().sync.route;
}
