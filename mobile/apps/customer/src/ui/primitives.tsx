import { type Icon as PhosphorIcon } from 'phosphor-react-native';
import { type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  type StyleProp,
  StyleSheet,
  Text,
  TextInput,
  type TextInputProps,
  type TextProps,
  type TextStyle,
  View,
  type ViewStyle} from 'react-native';

import { c, sp, t, type as typeRamp, type TypeVariant } from '@/theme';

/** Largest text scale honoured (Dynamic Type / Android font size); layouts use min heights so they grow with it. */
export const MAX_FONT_SCALE = 2;

export function AppText({ variant = 'body', color, style, ...rest }: TextProps & { variant?: TypeVariant; color?: string }) {
  return <Text allowFontScaling maxFontSizeMultiplier={MAX_FONT_SCALE} {...rest} style={[typeRamp[variant], { color: color ?? c.text }, style]} />;
}

export type ButtonKind = 'primary' | 'dark' | 'outline' | 'danger' | 'ghost' | 'muted' | 'onDark';

const KIND: Record<ButtonKind, { bg: string; fg: string; border: string }> = {
  primary: { bg: c.actionPrimary, fg: c.actionPrimaryText, border: c.actionPrimary },
  dark: { bg: t.ink['1000'], fg: c.textInverse, border: t.ink['1000'] },
  outline: { bg: c.surface, fg: c.text, border: c.borderStrong },
  danger: { bg: c.danger, fg: c.textInverse, border: c.danger },
  ghost: { bg: 'transparent', fg: c.text, border: 'transparent' },
  muted: { bg: t.ink['100'], fg: c.text, border: t.ink['100'] },
  /** Secondary action on a black canvas (welcome and onboarding screens). */
  onDark: { bg: 'transparent', fg: c.textInverse, border: t.ink['600'] }
};

export interface ButtonProps {
  label: string;
  onPress: () => void;
  kind?: ButtonKind;
  icon?: PhosphorIcon;
  disabled?: boolean;
  loading?: boolean;
  size?: 'md' | 'lg';
  style?: StyleProp<ViewStyle>;
  accessibilityLabel?: string;
}

export function Button({ label, onPress, kind = 'primary', icon: Icon, disabled, loading, size = 'lg', style, accessibilityLabel }: ButtonProps) {
  const k = KIND[kind];
  const off = disabled || loading;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? label}
      accessibilityState={{ disabled: !!off, busy: !!loading }}
      onPress={off ? undefined : onPress}
      style={({ pressed }) => [
        styles.btn,
        { minHeight: Math.max(size === 'lg' ? t.size.controlLg + 4 : t.size.control, t.size.touch), backgroundColor: k.bg, borderColor: k.border, opacity: off ? 0.5 : pressed ? 0.85 : 1 },
        style
      ]}
    >
      {loading ? <ActivityIndicator color={k.fg} /> : Icon ? <Icon size={20} color={k.fg} /> : null}
      <Text maxFontSizeMultiplier={MAX_FONT_SCALE} style={[typeRamp.bodyStrong, { color: k.fg, textDecorationLine: kind === 'ghost' ? 'underline' : 'none' }]}>{label}</Text>
    </Pressable>
  );
}

export function IconButton({ icon: Icon, onPress, label, tone = 'muted', size = 44, badge }: { icon: PhosphorIcon; onPress: () => void; label: string; tone?: 'muted' | 'clear' | 'dark'; size?: number; badge?: number }) {
  const bg = tone === 'muted' ? c.surfaceMuted : tone === 'dark' ? t.ink['1000'] : 'transparent';
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={badge ? `${label} (${badge})` : label} hitSlop={6} onPress={onPress} style={[styles.iconBtn, { width: size, height: size, borderRadius: size / 2, backgroundColor: bg }]}>
      <Icon size={22} color={tone === 'dark' ? c.textInverse : c.text} />
      {badge ? (
        <View style={styles.badge}>
          <Text style={styles.badgeText}>{badge > 99 ? '99+' : badge}</Text>
        </View>
      ) : null}
    </Pressable>
  );
}

export function Card({ children, style, onPress, selected, accessibilityLabel }: { children: ReactNode; style?: StyleProp<ViewStyle>; onPress?: () => void; selected?: boolean; accessibilityLabel?: string }) {
  const s = [styles.card, selected ? { borderColor: t.ink['1000'], backgroundColor: c.warningBg } : null, style];
  if (!onPress) return <View style={s}>{children}</View>;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel={accessibilityLabel} accessibilityState={{ selected: !!selected }} onPress={onPress} style={({ pressed }) => [s, pressed ? { opacity: 0.85 } : null]}>
      {children}
    </Pressable>
  );
}

export function Field({ label, error, hint, style, ...input }: TextInputProps & { label: string; error?: string; hint?: string; style?: StyleProp<TextStyle> }) {
  return (
    <View style={{ gap: sp['1'] }}>
      <AppText variant="small" color={c.textMuted} style={{ fontWeight: t.fontWeight.semibold }}>
        {label}
      </AppText>
      <TextInput
        placeholderTextColor={c.textSubtle}
        accessibilityLabel={label}
        accessibilityHint={error ?? hint}
        maxFontSizeMultiplier={MAX_FONT_SCALE}
        {...input}
        style={[styles.input, error ? { borderColor: c.danger } : null, style]}
      />
      {error ? <AppText variant="caption" color={c.danger} accessibilityRole="alert" accessibilityLiveRegion="polite">{error}</AppText> : hint ? <AppText variant="caption" color={c.textMuted}>{hint}</AppText> : null}
    </View>
  );
}

export function Banner({ tone, children, icon: Icon }: { tone: 'info' | 'danger' | 'success' | 'warning'; children: ReactNode; icon?: PhosphorIcon }) {
  const map = { info: [c.warningBg, c.text], danger: [c.dangerBg, c.danger], success: [c.successBg, c.success], warning: [c.warningBg, c.warning] } as const;
  const [bg, fg] = map[tone];
  return (
    <View accessibilityRole={tone === 'danger' ? 'alert' : undefined} style={[styles.banner, { backgroundColor: bg }]}>
      {Icon ? <Icon size={18} color={fg} /> : null}
      <AppText variant="small" color={fg} style={{ flex: 1 }}>
        {children}
      </AppText>
    </View>
  );
}

export function Chip({ label, selected, onPress }: { label: string; selected?: boolean; onPress: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected: !!selected }}
      onPress={onPress}
      style={[styles.chip, selected ? { borderColor: t.ink['1000'], backgroundColor: c.warningBg } : null]}
    >
      <AppText variant="small" style={{ fontWeight: selected ? t.fontWeight.bold : t.fontWeight.regular }}>
        {label}
      </AppText>
    </Pressable>
  );
}

export function SectionLabel({ children }: { children: ReactNode }) {
  return (
    <AppText variant="label" color={c.textMuted} accessibilityRole="header">
      {children}
    </AppText>
  );
}

export function KeyValue({ k, v, strong }: { k: string; v: string; strong?: boolean }) {
  return (
    <View style={styles.kv}>
      <AppText variant={strong ? 'bodyStrong' : 'body'} color={strong ? c.text : c.textMuted}>{k}</AppText>
      <AppText variant={strong ? 'bodyStrong' : 'body'}>{v}</AppText>
    </View>
  );
}

export function Loading() {
  return (
    <View style={styles.center} accessibilityRole="progressbar" accessibilityState={{ busy: true }}>
      <ActivityIndicator color={c.text} />
    </View>
  );
}

export function Empty({ icon: Icon, title, body, action }: { icon: PhosphorIcon; title: string; body?: string; action?: { label: string; onPress: () => void } }) {
  return (
    <View style={[styles.center, { gap: sp['3'], paddingVertical: sp['12'] }]}>
      <Icon size={48} color={c.textSubtle} />
      <AppText variant="heading" accessibilityRole="header">{title}</AppText>
      {body ? <AppText variant="small" color={c.textMuted} style={{ textAlign: 'center' }}>{body}</AppText> : null}
      {action ? <Button label={action.label} onPress={action.onPress} size="md" style={{ paddingHorizontal: sp['6'] }} /> : null}
    </View>
  );
}

export const styles = StyleSheet.create({
  btn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: sp['2'], borderRadius: t.radius.pill, borderWidth: 1, paddingHorizontal: sp['5'] },
  iconBtn: { alignItems: 'center', justifyContent: 'center' },
  badge: { position: 'absolute', top: 4, right: 4, minWidth: 16, height: 16, paddingHorizontal: 4, borderRadius: 8, backgroundColor: c.cartBadge, alignItems: 'center', justifyContent: 'center' },
  badgeText: { color: c.cartBadgeText, fontSize: 10, fontWeight: t.fontWeight.bold },
  card: { borderWidth: 1, borderColor: c.border, borderRadius: t.radius.lg, padding: sp['4'], backgroundColor: c.surface, gap: sp['2'] },
  input: { minHeight: t.size.controlLg, borderWidth: 1, borderColor: c.borderStrong, borderRadius: t.radius.md, paddingHorizontal: sp['3'], fontSize: t.fontSize.base, color: c.text, backgroundColor: c.surface },
  banner: { flexDirection: 'row', alignItems: 'center', gap: sp['2'], paddingVertical: 10, paddingHorizontal: sp['3'], borderRadius: t.radius.md },
  chip: { minHeight: t.size.touch, justifyContent: 'center', paddingHorizontal: sp['3'], paddingVertical: sp['2'], borderRadius: t.radius.pill, borderWidth: 1, borderColor: c.border, backgroundColor: c.surface },
  kv: { flexDirection: 'row', justifyContent: 'space-between', gap: sp['3'] },
  center: { alignItems: 'center', justifyContent: 'center', padding: sp['6'] }
});
