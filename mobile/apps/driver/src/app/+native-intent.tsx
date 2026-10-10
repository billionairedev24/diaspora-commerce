/* Deep links into the driver app (com.afrotransact.driver://stop/<id> from dispatch pushes). */
export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  const m = /^(?:com\.afrotransact\.driver:\/\/)?\/?(stop\/[^/?#]+|route|profile)?/.exec(path);
  return m?.[1] ? `/${m[1]}` : '/';
}
