/* expo-notifications, loaded on first use instead of at app start. Expo Go (SDK 53+) throws on Android as soon as a
   push token or token listener is requested and warns on import, so nothing imports the module eagerly: the web
   preview never loads it, Expo Go loads it only for local notification APIs (inbox, permission prompt) and only our
   own builds use the remote-push half (see capabilities.remotePush). */
import type * as ExpoNotifications from 'expo-notifications';

import { capabilities } from '@/platform/runtime';

export type NotificationsModule = typeof ExpoNotifications;

let loading: Promise<NotificationsModule | null> | null = null;

/** The module, or null where notifications don't exist (web) or it failed to load. */
export function loadNotifications(): Promise<NotificationsModule | null> {
  if (!capabilities.localNotifications) return Promise.resolve(null);
  loading ??= import('expo-notifications').then((m) => m as NotificationsModule, () => null);
  return loading;
}
