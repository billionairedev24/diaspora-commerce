/* S168: FCM/APNs push. The device push token is registered with identity (POST /v1/me/devices, app=customer) on every
   start and every token refresh, and unregistered on sign-out (DELETE /v1/me/devices). Tapping a notification opens
   the right screen; per-category preferences decide whether a push is shown while the app is open. */
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { useRouter } from 'expo-router';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Platform } from 'react-native';

import { useApi } from '@/api/ApiProvider';
import { type RegisterDeviceRequest } from '@/api/types';
import { useAuth } from '@/auth/AuthProvider';
import { type CategoryPrefs, DEFAULT_CATEGORY_PREFS, type PushData, routeForNotification, shouldDisplay } from '@/domain/notifications';

import { loadCategoryPrefs, saveCategoryPrefs } from './categoryPrefs';

interface PushContextValue {
  /** Asks for notification permission (setup step 4 / settings) and registers the device. */
  enable(): Promise<boolean>;
  categories: CategoryPrefs;
  setCategories(p: CategoryPrefs): Promise<void>;
  /** Server switches from identity (push, marketing) used to filter foreground notifications. */
  setServerPrefs(p: { push: boolean; marketingOptIn: boolean }): void;
}

const PushContext = createContext<PushContextValue | null>(null);
const supported = Platform.OS === 'ios' || Platform.OS === 'android';

/** Native device token (APNs on iOS, FCM on Android) for identity's push sender. */
export function registrationFor(token: string, platform: 'ios' | 'android', deviceName: string | null): RegisterDeviceRequest {
  return { app: 'customer', platform, token, provider: platform === 'ios' ? 'apns' : 'fcm', ...(deviceName ? { deviceName } : {}) };
}

export function PushProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const router = useRouter();
  const { phase, onSignOut } = useAuth();
  const [categories, setCats] = useState<CategoryPrefs>(DEFAULT_CATEGORY_PREFS);
  const server = useRef({ push: true, marketingOptIn: false });
  const cats = useRef(categories);
  cats.current = categories;
  const token = useRef<string | null>(null);
  const signedIn = phase === 'locked' || phase === 'unlocked';

  useEffect(() => { void loadCategoryPrefs().then(setCats); }, []);

  useEffect(() => {
    if (!supported) return;
    Notifications.setNotificationHandler({
      handleNotification: async (n) => {
        const data = (n.request.content.data ?? {}) as PushData;
        const show = shouldDisplay(data.category, server.current, cats.current);
        return { shouldShowBanner: show, shouldShowList: show, shouldPlaySound: show, shouldSetBadge: false };
      }
    });
  }, []);

  const register = useCallback(async (t: string) => {
    if (!supported) return;
    token.current = t;
    try {
      await api.identity.registerDevice(registrationFor(t, Platform.OS as 'ios' | 'android', Device.deviceName));
    } catch {
      /* retried on next start / token refresh */
    }
  }, [api]);

  const enable = useCallback(async () => {
    if (!supported || !Device.isDevice) return false;
    const current = await Notifications.getPermissionsAsync();
    const granted = current.granted || (await Notifications.requestPermissionsAsync()).granted;
    if (!granted) return false;
    if (Platform.OS === 'android') {
      await Notifications.setNotificationChannelAsync('orders', { name: 'Orders & deliveries', importance: Notifications.AndroidImportance.HIGH });
    }
    const t = await Notifications.getDevicePushTokenAsync();
    await register(String(t.data));
    return true;
  }, [register]);

  /* Register on every start (when signed in and permission was granted) and on token refresh. */
  useEffect(() => {
    if (!supported || !signedIn) return;
    let cancelled = false;
    void (async () => {
      const perm = await Notifications.getPermissionsAsync();
      if (!perm.granted || !Device.isDevice || cancelled) return;
      const t = await Notifications.getDevicePushTokenAsync();
      if (!cancelled) await register(String(t.data));
    })();
    const sub = Notifications.addPushTokenListener((t) => { void register(String(t.data)); });
    return () => { cancelled = true; sub.remove(); };
  }, [signedIn, register]);

  /* Unregister on sign-out. */
  useEffect(() => onSignOut(async () => {
    if (token.current) await api.identity.unregisterDevice(token.current);
    token.current = null;
  }), [api, onSignOut]);

  /* Deep links from taps (warm and cold start). */
  useEffect(() => {
    if (!supported || !signedIn) return;
    const open = (r: Notifications.NotificationResponse | null) => {
      if (!r) return;
      router.push(routeForNotification((r.notification.request.content.data ?? {}) as PushData) as never);
    };
    void Notifications.getLastNotificationResponseAsync().then(open);
    const sub = Notifications.addNotificationResponseReceivedListener(open);
    return () => sub.remove();
  }, [signedIn, router]);

  const value = useMemo<PushContextValue>(() => ({
    enable,
    categories,
    setCategories: async (p) => { setCats(p); await saveCategoryPrefs(p); },
    setServerPrefs: (p) => { server.current = p; }
  }), [enable, categories]);

  return <PushContext.Provider value={value}>{children}</PushContext.Provider>;
}

export function usePush(): PushContextValue {
  const ctx = useContext(PushContext);
  if (!ctx) throw new Error('usePush outside PushProvider');
  return ctx;
}
