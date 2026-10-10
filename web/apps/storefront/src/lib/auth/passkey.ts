import { type ApiClient, type AuthResult } from '@afrotransact/api-client';

const fromB64url = (s: string): Uint8Array<ArrayBuffer> => {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(bin.length));
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
};

const toB64url = (buf: ArrayBuffer | null): string => {
  if (!buf) return '';
  let s = '';
  new Uint8Array(buf).forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

export type PasskeyFailure = 'unsupported' | 'cancelled' | 'failed';

export class PasskeyError extends Error {
  constructor(readonly reason: PasskeyFailure) {
    super(reason);
  }
}

/** WebAuthn sign-in with a discoverable credential: BFF options → navigator.credentials.get → BFF verify. */
export async function signInWithPasskey(api: ApiClient): Promise<AuthResult> {
  if (typeof window === 'undefined' || !window.PublicKeyCredential || !navigator.credentials?.get) throw new PasskeyError('unsupported');
  const opts = await api.passkeyOptions();
  let cred: Credential | null;
  try {
    cred = await navigator.credentials.get({
      publicKey: {
        challenge: fromB64url(opts.challenge),
        rpId: opts.rpId,
        timeout: opts.timeout,
        userVerification: opts.userVerification,
        allowCredentials: opts.allowCredentials.map((c) => ({ id: fromB64url(c.id), type: c.type }))
      }
    });
  } catch (e) {
    const name = (e as { name?: string })?.name;
    throw new PasskeyError(name === 'NotAllowedError' || name === 'AbortError' ? 'cancelled' : 'failed');
  }
  if (!cred || !(cred instanceof PublicKeyCredential)) throw new PasskeyError('cancelled');
  const r = cred.response as AuthenticatorAssertionResponse;
  return api.passkeyVerify({
    challengeId: opts.challengeId,
    id: cred.id,
    rawId: toB64url(cred.rawId),
    type: 'public-key',
    response: { clientDataJSON: toB64url(r.clientDataJSON), authenticatorData: toB64url(r.authenticatorData), signature: toB64url(r.signature), userHandle: r.userHandle ? toB64url(r.userHandle) : null }
  });
}
