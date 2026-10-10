/* Design tokens for React Native, from @afrotransact/tokens (the single CSS source in web/packages/tokens).
   Components use only these values — never raw hex colours (enforced by ESLint at-mobile/no-raw-hex). */
import { nativeTokens } from '@afrotransact/tokens/native';

export const t = nativeTokens;
export const c = nativeTokens.color;
export const sp = nativeTokens.space;

/** Type ramp used by the app (sizes from the token scale, line heights from leading tokens). */
export const type = {
  display: { fontSize: t.fontSize['2xl'], fontWeight: t.fontWeight.black, lineHeight: t.fontSize['2xl'] * t.leading.tight },
  title: { fontSize: t.fontSize.xl, fontWeight: t.fontWeight.bold, lineHeight: t.fontSize.xl * t.leading.tight },
  heading: { fontSize: t.fontSize.lg, fontWeight: t.fontWeight.bold, lineHeight: t.fontSize.lg * t.leading.tight },
  body: { fontSize: t.fontSize.base, fontWeight: t.fontWeight.regular, lineHeight: t.fontSize.base * t.leading.normal },
  bodyStrong: { fontSize: t.fontSize.base, fontWeight: t.fontWeight.semibold, lineHeight: t.fontSize.base * t.leading.normal },
  small: { fontSize: t.fontSize.sm, fontWeight: t.fontWeight.regular, lineHeight: t.fontSize.sm * t.leading.normal },
  caption: { fontSize: t.fontSize.xs, fontWeight: t.fontWeight.regular, lineHeight: t.fontSize.xs * t.leading.normal },
  label: { fontSize: t.fontSize['2xs'], fontWeight: t.fontWeight.bold, lineHeight: t.fontSize['2xs'] * t.leading.normal, letterSpacing: 0.66 }
} as const;

export type TypeVariant = keyof typeof type;
