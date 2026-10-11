/* Per-viewer conveniences (visible columns, widths, density) persist in localStorage; every access is guarded because
   storage can be unavailable (private windows, blocked site data). */

const KEY = (id: string) => `at.table.${id}`;

export interface TablePrefs {
  visibility?: Record<string, boolean>;
  sizing?: Record<string, number>;
  density?: 'comfortable' | 'compact';
  /** Rows per page chosen for this table. */
  pageSize?: number;
}

export function readPrefs(id: string): TablePrefs {
  try {
    const raw = window.localStorage.getItem(KEY(id));
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as TablePrefs) : {};
  } catch {
    return {};
  }
}

export function writePrefs(id: string, prefs: TablePrefs): void {
  try {
    window.localStorage.setItem(KEY(id), JSON.stringify(prefs));
  } catch {
    // Storage full or blocked: the table still works, the preference just isn't remembered.
  }
}

const DEFAULT_DENSITY = 'at.table.defaultDensity';

/** The viewer's default row density from My account › Preferences (tables they never adjusted use it). */
export function readDefaultDensity(): 'comfortable' | 'compact' | undefined {
  try {
    const v = window.localStorage.getItem(DEFAULT_DENSITY);
    return v === 'compact' || v === 'comfortable' ? v : undefined;
  } catch {
    return undefined;
  }
}

export function writeDefaultDensity(density: 'comfortable' | 'compact'): void {
  try {
    window.localStorage.setItem(DEFAULT_DENSITY, density);
  } catch {
    // Blocked storage: tables fall back to comfortable rows.
  }
}
