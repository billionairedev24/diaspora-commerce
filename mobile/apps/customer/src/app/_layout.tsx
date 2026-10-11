/* Root: providers, the sign-in gate (no guest mode) and the lock overlay that keeps your place while locked. */
import '@/telemetry';

import { getTelemetry } from '@afrotransact/telemetry';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { ApiProvider } from '@/api/ApiProvider';
import { AuthProvider, useAuth } from '@/auth/AuthProvider';
import { BootScreen } from '@/brand/BootScreen';
import { LockOverlay } from '@/features/lock/LockOverlay';
import { PasscodePrompt } from '@/features/lock/PasscodePrompt';
import { AppI18nProvider } from '@/i18n';
import { PaymentsProvider } from '@/payments/PaymentsProvider';
import { PushProvider } from '@/push/PushProvider';
import { c } from '@/theme';
import { CrashScreen } from '@/ui/CrashScreen';
import { DevBuildNote } from '@/ui/DevBuildNote';
import { ToastProvider } from '@/ui/Toast';

function Gate() {
  const { ready, phase } = useAuth();
  if (!ready) return <BootScreen />;
  const inApp = phase === 'locked' || phase === 'unlocked';
  return (
    <View style={{ flex: 1, backgroundColor: c.canvas }}>
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: c.canvas } }}>
        <Stack.Protected guard={phase === 'signed-out'}>
          <Stack.Screen name="sign-in" />
          <Stack.Screen name="auth/callback" />
        </Stack.Protected>
        <Stack.Protected guard={phase === 'setup'}>
          <Stack.Screen name="setup" options={{ gestureEnabled: false }} />
        </Stack.Protected>
        <Stack.Protected guard={inApp}>
          <Stack.Screen name="(app)" />
        </Stack.Protected>
      </Stack>
      {phase === 'locked' ? <LockOverlay /> : null}
      <PasscodePrompt />
      <DevBuildNote />
    </View>
  );
}

export default function RootLayout() {
  return (
    <SafeAreaProvider>
      <AppI18nProvider>
      <StatusBar style="dark" />
      <AuthProvider>
        <ApiProvider>
          <PaymentsProvider>
            <PushProvider>
              <ToastProvider>
                <Gate />
              </ToastProvider>
            </PushProvider>
          </PaymentsProvider>
        </ApiProvider>
      </AuthProvider>
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

