/* Root: session gate — sign-in, "No driver access", or the app. */
import '@/telemetry';

import { getTelemetry } from '@afrotransact/telemetry';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { BootScreen } from '@/brand/BootScreen';
import { AppI18nProvider } from '@/i18n';
import { DriverProvider, useDriver } from '@/session/DriverProvider';
import { c } from '@/theme';
import { CrashScreen } from '@/ui/CrashScreen';
import { DevBuildNote } from '@/ui/DevBuildNote';
import { ToastProvider } from '@/ui/Toast';

function Gate() {
  const { phase } = useDriver();
  if (phase === 'booting') return <BootScreen />;
  return (
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
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AppI18nProvider>
      <StatusBar style="dark" />
      <ToastProvider>
        <DriverProvider>
          <Gate />
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

