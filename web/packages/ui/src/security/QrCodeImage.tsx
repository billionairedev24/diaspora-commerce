import { create, type QRCodeErrorCorrectionLevel } from 'qrcode';

/** Light margin around the symbol, in modules (ISO/IEC 18004 asks for 4). */
export const QR_QUIET_ZONE = 4;

export interface QrMatrix {
  /** Modules per side, without the quiet zone. */
  size: number;
  /** Row-major dark modules. */
  dark: boolean[];
}

/** Encodes text as a QR symbol (byte mode, version chosen by the library, error correction M by default). */
export function qrMatrix(value: string, level: QRCodeErrorCorrectionLevel = 'M'): QrMatrix {
  const { modules } = create(value, { errorCorrectionLevel: level });
  const dark: boolean[] = [];
  for (let r = 0; r < modules.size; r++) for (let c = 0; c < modules.size; c++) dark.push(Boolean(modules.get(r, c)));
  return { size: modules.size, dark };
}

/** One SVG path of 1×1 squares, offset by the quiet zone (unit = one module). */
export function qrPath({ size, dark }: QrMatrix): string {
  let d = '';
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) if (dark[r * size + c]) d += `M${c + QR_QUIET_ZONE} ${r + QR_QUIET_ZONE}h1v1h-1z`;
  }
  return d;
}

export interface QrCodeImageProps {
  /** The text to encode, e.g. an otpauth:// URI. */
  value: string;
  /** Accessible name ("QR code for your authenticator app"). */
  label: string;
  /** Rendered width and height in px (the symbol scales crisply). */
  size?: number;
  level?: QRCodeErrorCorrectionLevel;
  className?: string;
}

/**
 * A scannable QR code as SVG: dark modules in the ink token on a light surface with a 4-module quiet zone, so phone
 * cameras and authenticator apps read it in both light and dark surroundings.
 */
export function QrCodeImage({ value, label, size = 192, level = 'M', className }: QrCodeImageProps) {
  const m = qrMatrix(value, level);
  const box = m.size + QR_QUIET_ZONE * 2;
  return (
    <svg
      className={className}
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${box} ${box}`}
      shapeRendering="crispEdges"
      data-qr-size={m.size}
    >
      <rect width={box} height={box} fill="var(--at-ink-0)" />
      <path d={qrPath(m)} fill="var(--at-ink-1000)" />
    </svg>
  );
}
