/* Root: session gate — sign-in, "No driver access", or the app. */
import '@/telemetry';

import { getTelemetry } from '@afrotransact/telemetry';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { BootScreen } from '@/brand/BootScreen';
import { LockLayer, useLockScreen } from '@/features/lock/LockLayer';
import { AppI18nProvider } from '@/i18n';
import { DriverProvider, useDriver } from '@/session/DriverProvider';
import { LockProvider, useLock } from '@/session/LockProvider';
import { c } from '@/theme';
import { CrashScreen } from '@/ui/CrashScreen';
import { DevBuildNote } from '@/ui/DevBuildNote';
import { ToastProvider } from '@/ui/Toast';

function Gate() {
  const { phase } = useDriver();
  const lock = useLock();
  const covered = useLockScreen() !== null;
  if (phase === 'booting' || !lock.ready) return <BootScreen />;
  /* The lock is a layer: the navigator stays mounted underneath (screen state, camera, signature pad survive), hidden
     from screen readers while the lock is up. */
  return (
    <View style={{ flex: 1 }}>
    <View style={{ flex: 1 }} accessibilityElementsHidden={covered} importantForAccessibility={covered ? 'no-hide-descendants' : 'auto'}>
    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: c.canvas } }}>
      <Stack.Protected guard={phase === 'signed-out'}>
        <Stack.Screen name="sign-in" />
        <Stack.Screen name="auth/callback" />
      </Stack.Protected>
      <Stack.Protected guard={phase === 'denied'}>
        <Stack.Screen name="no-access" />
      </Stack.Protected>
      <Stack.Protected guard={phase === 'ready' || phase === 'checking'}>
        <Stack.Screen name="(app)" />
      </Stack.Protected>
    </Stack>
    </View>
    <LockLayer />
    </View>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AppI18nProvider>
      <StatusBar style="dark" />
      <ToastProvider>
        <DriverProvider>
          <LockProvider>
            <Gate />
          </LockProvider>
          <DevBuildNote />
        </DriverProvider>
      </ToastProvider>
      </AppI18nProvider>
    </SafeAreaProvider>
  );
}
/** Route error boundary (expo-router): reports the crash to telemetry and offers a retry. */
export function ErrorBoundary({ error, retry }: { error: Error; retry: () => Promise<void> }) {
  useEffect(() => {
    getTelemetry().captureException(error, { tags: { boundary: 'root' } });
  }, [error]);
  return <CrashScreen onRetry={() => void retry()} />;
}

