import { type ReactNode } from 'react';

const clamp: Record<number, string> = { 1: 'line-clamp-1', 2: 'line-clamp-2', 3: 'line-clamp-3' };

/**
 * Text in a cell of a fixed-height row: at most `lines` lines (default one), then an ellipsis, with the whole text as a
 * tooltip, so nothing is ever cut off without a sign. `muted` is the small second line style.
 */
export function CellText({ children, lines = 1, muted = false, mono = false, title }: { children: string; lines?: 1 | 2 | 3; muted?: boolean; mono?: boolean; title?: string }) {
  return (
    <span title={title ?? children} className={`min-w-0 whitespace-normal ${clamp[lines]} [overflow-wrap:anywhere] ${muted ? 'text-xs text-muted' : ''} ${mono ? 'font-mono text-xs text-muted' : ''}`}>
      {children}
    </span>
  );
}

/**
 * The first column of a list of things: a 40px photo (a plain flat box when there is none, no icon, no emoji), the name in
 * bold on at most two lines and one small muted line under it. Vertically centred in its row; the text never runs under the photo.
 */
export function ItemCell({ imageUrl, title, subtitle }: { imageUrl?: string | null; title: string; subtitle?: ReactNode }) {
  return (
    <span className="flex min-w-0 items-center gap-3 whitespace-normal">
      {imageUrl ? (
        <img src={imageUrl} alt="" width={40} height={40} loading="lazy" className="size-10 shrink-0 rounded-sm border border-border bg-surface-muted object-cover" />
      ) : (
        <span aria-hidden className="size-10 shrink-0 rounded-sm border border-border bg-surface-muted" />
      )}
      <span className="flex min-w-0 flex-col justify-center gap-0.5">
        <strong title={title} className="line-clamp-2 min-w-0 leading-tight font-semibold [overflow-wrap:anywhere]">
          {title}
        </strong>
        {subtitle ? <span className="block truncate text-xs text-muted">{subtitle}</span> : null}
      </span>
    </span>
  );
}
