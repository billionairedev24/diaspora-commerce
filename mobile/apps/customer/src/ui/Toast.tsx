import { CheckCircleIcon as CheckCircle } from 'phosphor-react-native';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { c, sp, t } from '@/theme';

import { AppText } from './primitives';

const ToastContext = createContext<(message: string) => void>(() => undefined);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const insets = useSafeAreaInsets();
  const show = useCallback((m: string) => {
    if (timer.current) clearTimeout(timer.current);
    setMessage(m);
    timer.current = setTimeout(() => setMessage(''), 2600);
  }, []);
  useEffect(() => () => { if (timer.current) clearTimeout(timer.current); }, []);
  const value = useMemo(() => show, [show]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      {message ? (
        <View pointerEvents="none" style={[styles.wrap, { top: insets.top + sp['2'] }]}>
          <View accessibilityLiveRegion="polite" accessibilityRole="alert" style={styles.toast}>
            <CheckCircle size={18} weight="fill" color={t.gold['500']} />
            <AppText variant="small" color={c.textInverse} style={{ flexShrink: 1 }}>{message}</AppText>
          </View>
        </View>
      ) : null}
    </ToastContext.Provider>
  );
}

export function useToast(): (message: string) => void {
  return useContext(ToastContext);
}

const styles = StyleSheet.create({
  wrap: { position: 'absolute', left: sp['4'], right: sp['4'], alignItems: 'center' },
  toast: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingVertical: 10, paddingHorizontal: sp['4'], borderRadius: t.radius.pill, backgroundColor: t.ink['1000'], ...t.shadow.md }
});
