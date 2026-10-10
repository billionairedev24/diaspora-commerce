import { cssVariables, resolvedVariables, type TokenName } from '@afrotransact/tokens';
import { type CSSProperties, type ReactNode } from 'react';

/* Storybook-only helpers that render the design tokens (@afrotransact/tokens) straight from the generated token map,
   so the docs never drift from tokens.css. Values are shown as declared (with var() references) and resolved. */

const names = Object.keys(cssVariables) as TokenName[];
const byPrefix = (prefix: string) => names.filter((n) => n.startsWith(prefix));
const declared = (n: TokenName) => cssVariables[n];
const resolved = (n: TokenName) => (resolvedVariables as Record<string, string>)[n] ?? cssVariables[n];

const cell: CSSProperties = { padding: '6px 10px', borderBottom: '1px solid var(--at-color-border)', textAlign: 'start', verticalAlign: 'middle' };
const mono: CSSProperties = { fontFamily: 'var(--at-font-mono)', fontSize: 'var(--at-text-xs)' };

function Table({ head, children }: { head: string[]; children: ReactNode }) {
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%', fontSize: 'var(--at-text-sm)', marginBottom: 24 }}>
      <thead>
        <tr>{head.map((h) => <th key={h} scope="col" style={{ ...cell, fontWeight: 700 }}>{h}</th>)}</tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  );
}

function ValueCells({ name }: { name: TokenName }) {
  const d = declared(name);
  const r = resolved(name);
  return (
    <>
      <td style={{ ...cell, ...mono }}>{name}</td>
      <td style={{ ...cell, ...mono }}>{d}{r !== d ? <><br /><span style={{ color: 'var(--at-color-text-muted)' }}>→ {r}</span></> : null}</td>
    </>
  );
}

/** Raw colour scales (gold, green, ink, red, sand): swatch grid per scale. */
export function ColorScales() {
  const scales = ['gold', 'green', 'ink', 'red', 'sand'];
  return (
    <div style={{ display: 'grid', gap: 16 }}>
      {scales.map((s) => (
        <section key={s} aria-label={`${s} scale`}>
          <h4 style={{ margin: '0 0 8px', textTransform: 'capitalize' }}>{s}</h4>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))', gap: 8 }}>
            {byPrefix(`--at-${s}-`).map((n) => (
              <figure key={n} style={{ margin: 0 }}>
                <div style={{ height: 48, borderRadius: 'var(--at-radius-sm)', background: `var(${n})`, border: '1px solid var(--at-color-border)' }} />
                <figcaption style={{ ...mono, marginTop: 4 }}>{n.replace('--at-', '')}<br />{resolved(n)}</figcaption>
              </figure>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}

/** Semantic colour roles (--at-color-*): what components should use. */
export function SemanticColors() {
  return (
    <Table head={['Swatch', 'Token', 'Value']}>
      {byPrefix('--at-color-').map((n) => (
        <tr key={n}>
          <td style={cell}><div style={{ width: 40, height: 24, borderRadius: 'var(--at-radius-xs)', background: `var(${n})`, border: '1px solid var(--at-color-border)' }} /></td>
          <ValueCells name={n} />
        </tr>
      ))}
    </Table>
  );
}

/** Font families, the type scale, weights, leading and tracking. */
export function Typography() {
  return (
    <>
      <Table head={['Sample', 'Token', 'Value']}>
        {byPrefix('--at-text-').map((n) => (
          <tr key={n}>
            <td style={{ ...cell, fontSize: `var(${n})`, whiteSpace: 'nowrap' }}>Jollof, sorted.</td>
            <ValueCells name={n} />
          </tr>
        ))}
      </Table>
      <Table head={['Sample', 'Token', 'Value']}>
        {[...byPrefix('--at-font-'), ...byPrefix('--at-weight-'), ...byPrefix('--at-leading-'), ...byPrefix('--at-tracking-')].map((n) => {
          const style: CSSProperties = n.startsWith('--at-font-') ? { fontFamily: `var(${n})` }
            : n.startsWith('--at-weight-') ? { fontWeight: `var(${n})` as CSSProperties['fontWeight'] }
            : n.startsWith('--at-leading-') ? { lineHeight: `var(${n})` }
            : { letterSpacing: `var(${n})` };
          return (
            <tr key={n}>
              <td style={{ ...cell, ...style }}>AfroTransact Brand</td>
              <ValueCells name={n} />
            </tr>
          );
        })}
      </Table>
    </>
  );
}

/** Spacing scale, control sizes and page metrics as bars. */
export function Spacing() {
  return (
    <Table head={['Size', 'Token', 'Value']}>
      {[...byPrefix('--at-space-'), ...byPrefix('--at-size-')].map((n) => (
        <tr key={n}>
          <td style={cell}><div style={{ height: 12, width: `var(${n})`, background: 'var(--at-color-action-primary)', borderRadius: 2 }} /></td>
          <ValueCells name={n} />
        </tr>
      ))}
    </Table>
  );
}

/** Radii, shadows, z-index layers and motion. */
export function Shape() {
  return (
    <>
      <Table head={['Sample', 'Token', 'Value']}>
        {byPrefix('--at-radius-').map((n) => (
          <tr key={n}>
            <td style={cell}><div style={{ width: 64, height: 40, borderRadius: `var(${n})`, border: '2px solid var(--at-color-border-strong)' }} /></td>
            <ValueCells name={n} />
          </tr>
        ))}
        {byPrefix('--at-shadow-').map((n) => (
          <tr key={n}>
            <td style={cell}><div style={{ width: 64, height: 40, borderRadius: 'var(--at-radius-md)', background: 'var(--at-color-surface)', boxShadow: `var(${n})` }} /></td>
            <ValueCells name={n} />
          </tr>
        ))}
      </Table>
      <Table head={['Token', 'Value']}>
        {[...byPrefix('--at-z-'), ...byPrefix('--at-duration'), ...byPrefix('--at-ease')].map((n) => (
          <tr key={n}><ValueCells name={n} /></tr>
        ))}
      </Table>
    </>
  );
}
