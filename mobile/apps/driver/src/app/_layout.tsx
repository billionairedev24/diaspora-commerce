/* Root: session gate — sign-in, "No driver access", or the app. */
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { DriverProvider, useDriver } from '@/session/DriverProvider';
import { c } from '@/theme';
import { Loading } from '@/ui/primitives';
import { ToastProvider } from '@/ui/Toast';

function Gate() {
  const { phase } = useDriver();
  if (phase === 'booting') return <Loading />;
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
      <StatusBar style="dark" />
      <ToastProvider>
        <DriverProvider>
          <Gate />
        </DriverProvider>
      </ToastProvider>
    </SafeAreaProvider>
  );
}
