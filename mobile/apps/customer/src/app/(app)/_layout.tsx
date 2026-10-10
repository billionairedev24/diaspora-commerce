import { Stack } from 'expo-router';

import { c } from '@/theme';

/** Signed-in area: tabs plus inner pages (each inner page pins its own title and back button). */
export default function AppLayout() {
  return <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: c.canvas } }} />;
}
