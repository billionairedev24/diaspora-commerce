import { cssVariables, resolvedVariables, tokens } from './generated/tokens';

export { cssVariables, resolvedVariables, tokens };

/** Name of any declared token, e.g. "--at-color-canvas". */
export type TokenName = keyof typeof cssVariables;

/** Typed reference to a CSS custom property: `cssVar('--at-color-link')` → "var(--at-color-link)". */
export function cssVar(name: TokenName): `var(${TokenName})` {
  return `var(${name})`;
}
