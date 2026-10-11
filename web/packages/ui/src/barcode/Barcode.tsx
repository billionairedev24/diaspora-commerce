import { type Bar, encode, modules, type Symbology } from './encode';

export interface BarcodeProps {
  /** Code 128 text, GS1 element string "(01)…(17)…(10)…", or 12 / 13 GTIN digits. */
  value: string;
  symbology?: Symbology;
  /** Bar height in px. */
  height?: number;
  /** Width of one module in px (2 prints well at 203 dpi). */
  module?: number;
  /** Print the human-readable line (default on). */
  showText?: boolean;
  className?: string;
}

/**
 * A scannable barcode as SVG (bars in currentColor on a transparent ground, so it follows the label's ink token).
 * Invalid data renders an accessible error instead of a misleading symbol.
 */
export function Barcode({ value, symbology = 'code128', height = 56, module = 2, showText = true, className }: BarcodeProps) {
  let bars: Bar[];
  let text: string;
  try {
    ({ bars, text } = encode(symbology, value));
  } catch (e) {
    return (
      <span role="alert" className={className}>
        Cannot encode “{value}”: {(e as Error).message}
      </span>
    );
  }
  const width = modules(bars) * module;
  const textHeight = showText ? 16 : 0;
  let x = 0;
  const rects = bars.map((b, i) => {
    const r = b.on ? <rect key={i} x={x} y={0} width={b.w * module} height={height} /> : null;
    x += b.w * module;
    return r;
  });
  return (
    <svg
      className={className}
      role="img"
      aria-label={`${symbology === 'gs1-128' ? 'GS1-128' : symbology === 'ean13' ? 'EAN-13' : 'Code 128'} barcode ${text}`}
      width={width}
      height={height + textHeight}
      viewBox={`0 0 ${width} ${height + textHeight}`}
      fill="currentColor"
      data-symbology={symbology}
      data-value={value}
    >
      {rects}
      {showText ? (
        <text x={width / 2} y={height + 13} textAnchor="middle" fontSize={12} fontFamily="ui-monospace, monospace">
          {text}
        </text>
      ) : null}
    </svg>
  );
}
