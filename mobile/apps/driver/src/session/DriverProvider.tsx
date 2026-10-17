/* Session + sync wiring for the driver app.
   S081: OIDC sign-in, then GET /v1/driver/me decides access (No driver access / revoked). Revocation — from /me, from
   `signOut: true` on a sync, or on the periodic re-check — signs the driver out after a last best-effort sync, and the
   outbox stays on the phone (events captured before revokedAt are still accepted later).
   S087: the SyncEngine queues every action in SQLite and syncs in order when online.
   S255: while on shift and in the foreground, location pings (throttled) feed nearest-driver pickup offers; the
   newest fix also stamps queued events. */
import * as Crypto from 'expo-crypto';
import * as Device from 'expo-device';
import * as Network from 'expo-network';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { AppState, Platform } from 'react-native';

import { createDriverApi, type DriverApi } from '@/api/client';
import { type TokenSource } from '@/api/http';
import { beginNativeSignIn, type NativeAttempt } from '@/auth/nativeAuth';
import { refreshTokens, revokeTokens, SessionGone, type TokenSet } from '@/auth/oidc';
import { secureStorage } from '@/auth/secureStorage';
import { decideAccess } from '@/domain/access';
import { decodeJwt, isExpiring } from '@/domain/jwt';
import { type SecondFactor, type SignInStep } from '@/domain/nativeSignIn';
import { onShift } from '@/domain/shift';
import { currentFix, ensureLocationPermission, watchForegroundFixes } from '@/lib/location';
import { capabilities } from '@/platform/runtime';
import { driverStore } from '@/storage';
import { type EngineState, SyncEngine } from '@/sync/engine';
import { LocationPinger } from '@/sync/locationPinger';

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
  verifySecondFactor(factor: SecondFactor, code: string, trustDevice: boolean): Promise<SignInStep>;
  cancelSignIn(): void;
  /** Set when the session ended on its own (the refresh token was refused). */
  notice: string;
  signOut(): Promise<void>;
  recheckAccess(): Promise<void>;
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
  const [notice, setNotice] = useState('');
  const [phase, setPhase] = useState<Phase>('booting');
  const [user, setUser] = useState<DriverUser | null>(null);
  const [denied, setDenied] = useState<Ctx['denied']>(null);
  const [paused, setPausedState] = useState(false);
  const [netUp, setNetUp] = useState(true);
  const [appActive, setAppActive] = useState(AppState.currentState !== 'background');

  const tokenSource = useMemo<TokenSource>(() => {
    const refreshAccessToken = async (): Promise<string | null> => {
      if (!tokens.current) return null;
      refreshing.current ??= refreshTokens(tokens.current)
        .then(async (t) => { tokens.current = t; await secureStorage.set(TOKENS, JSON.stringify(t)); return t.accessToken; })
        .catch((e: unknown) => { if (e instanceof SessionGone) return null; throw e; })
        .finally(() => { refreshing.current = null; });
      return refreshing.current;
    };
    return {
      refreshAccessToken,
      onSessionExpired: () => expire.current(),
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

  const clearSession = useCallback(async () => {
    if (pushToken.current) await api.unregisterDevice(pushToken.current).catch(() => undefined);
    pushToken.current = null;
    if (tokens.current) await revokeTokens(tokens.current);
    tokens.current = null;
    await secureStorage.remove(TOKENS);
    engine.setUser(null); // the outbox stays on the phone
  }, [api, engine]);

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
      if (!raw) return setPhase('signed-out');
      tokens.current = JSON.parse(raw) as TokenSet;
      const u = userOf(tokens.current);
      setUser(u);
      engine.setUser(u.sub);
      await checkAccess(u);
    })();
  }, [engine, checkAccess]);

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
    setNotice('');
    const u = userOf(t);
    setUser(u);
    engine.setUser(u.sub);
    void ensureLocationPermission();
    await checkAccess(u);
    if (engine.getState().route) void engine.record({ type: 'session.signed_in' }).catch(() => undefined);
  }, [engine, checkAccess]);

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

  const signOut = useCallback(async () => {
    await engine.flush().catch(() => undefined);
    await clearSession();
    setUser(null);
    setDenied(null);
    setPhase('signed-out');
  }, [clearSession, engine]);

  expire.current = () => {
    if (phase === 'signed-out') return;
    void (async () => {
      await clearSession();
      setUser(null);
      setDenied(null);
      setNotice('Your session ended. Sign in again.');
      setPhase('signed-out');
    })();
  };

  const value = useMemo<Ctx>(() => ({
    phase, user, denied, engine, sync, api, paused,
    setPaused: (p) => { setPausedState(p); void driverStore.setKv(PAUSED, p); },
    signInWithPassword, sendSignInCode, verifySecondFactor, cancelSignIn, notice, signOut,
    recheckAccess: async () => { if (user) await checkAccess(user); }
  }), [phase, user, denied, engine, sync, api, paused, signInWithPassword, sendSignInCode, verifySecondFactor, cancelSignIn, notice, signOut, checkAccess]);

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
