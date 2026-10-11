import { beforeEach, describe, expect, it, vi } from 'vitest';

/* Expo Go guards: no push-token registration in Expo Go (SDK 53+ removed it), everything else unchanged. */
const native = vi.hoisted(() => ({ executionEnvironment: 'storeClient' as string | undefined, os: 'android' }));

vi.mock('expo-constants', () => ({
  default: { get executionEnvironment() { return native.executionEnvironment; } },
  ExecutionEnvironment: { Bare: 'bare', Standalone: 'standalone', StoreClient: 'storeClient' }
}));
vi.mock('react-native', () => ({ Platform: { get OS() { return native.os; } } }));
vi.mock('expo-notifications', () => ({ getDevicePushTokenAsync: vi.fn() }));

async function load() {
  vi.resetModules();
  return import('@/platform/runtime');
}

describe('driver runtime capabilities', () => {
  beforeEach(() => {
    native.executionEnvironment = 'storeClient';
    native.os = 'android';
  });

  it('skips remote push in Expo Go', async () => {
    const { capabilities, isExpoGo } = await load();
    expect(isExpoGo()).toBe(true);
    expect(capabilities).toEqual({ runtime: 'expo-go', remotePush: false, localNotifications: true });
  });

  it('registers push in a development or release build', async () => {
    native.executionEnvironment = 'bare';
    const { capabilities } = await load();
    expect(capabilities).toEqual({ runtime: 'native-build', remotePush: true, localNotifications: true });
  });

  it('never loads expo-notifications on the web preview', async () => {
    native.os = 'web';
    vi.resetModules();
    const { loadNotifications } = await import('@/session/notificationsModule');
    await expect(loadNotifications()).resolves.toBeNull();
  });
});
