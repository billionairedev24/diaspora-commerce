import * as Crypto from 'expo-crypto';
import { File } from 'expo-file-system';

/** SHA-256 (hex) of a captured photo, sent with its capture metadata. */
export async function sha256File(uri: string): Promise<string> {
  const bytes = new Uint8Array(await new File(uri).arrayBuffer());
  const digest = await Crypto.digest(Crypto.CryptoDigestAlgorithm.SHA256, bytes);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}
