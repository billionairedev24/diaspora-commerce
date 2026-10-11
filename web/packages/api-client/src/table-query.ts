/** Query string of a server-driven table: the values plus `sort=<column>,asc|desc`, repeated per sort column. */
export function tableSearchParams(values: Record<string, string | number | undefined>, sort?: readonly string[]): URLSearchParams {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== undefined && v !== '') p.set(k, String(v));
  });
  (sort ?? []).forEach((s) => p.append('sort', s));
  return p;
}
