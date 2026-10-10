/* Line diff for template version history (longest common subsequence; templates are small). */

export interface DiffLine {
  kind: 'same' | 'added' | 'removed';
  text: string;
}

/** Above this many lines per side the diff falls back to "everything removed, everything added" (no quadratic blow-up). */
const MAX_LINES = 1500;

export function diffLines(before: string, after: string): DiffLine[] {
  const a = before === '' ? [] : before.split('\n');
  const b = after === '' ? [] : after.split('\n');
  if (a.length > MAX_LINES || b.length > MAX_LINES) {
    return [...a.map((text): DiffLine => ({ kind: 'removed', text })), ...b.map((text): DiffLine => ({ kind: 'added', text }))];
  }
  // lcs[i][j] = length of the LCS of a[i..] and b[j..]
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    const row = lcs[i] as number[];
    const next = lcs[i + 1] as number[];
    for (let j = b.length - 1; j >= 0; j--) {
      row[j] = a[i] === b[j] ? (next[j + 1] as number) + 1 : Math.max(next[j] as number, row[j + 1] as number);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] as string });
      i++;
      j++;
    } else if ((lcs[i + 1]?.[j] ?? 0) >= (lcs[i]?.[j + 1] ?? 0)) {
      out.push({ kind: 'removed', text: a[i] as string });
      i++;
    } else {
      out.push({ kind: 'added', text: b[j] as string });
      j++;
    }
  }
  while (i < a.length) out.push({ kind: 'removed', text: a[i++] as string });
  while (j < b.length) out.push({ kind: 'added', text: b[j++] as string });
  return out;
}

/** Whether a diff has any change. */
export const hasChanges = (lines: DiffLine[]): boolean => lines.some((l) => l.kind !== 'same');
