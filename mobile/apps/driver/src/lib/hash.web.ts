/* Web preview: hash the data URL text with SubtleCrypto (native builds hash the file bytes). */
export async function sha256File(uri: string): Promise<string> {
  const data = new TextEncoder().encode(uri);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}
