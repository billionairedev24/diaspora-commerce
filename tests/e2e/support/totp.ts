import { createHmac } from 'node:crypto';

import { expect, type Page } from '@playwright/test';

/* RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) — what an authenticator app computes from the setup key. */

function base32(secret: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of secret.replace(/[\s=]/g, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

export function totp(secret: string, at = Date.now()): string {
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(Math.floor(at / 30_000)));
  const h = createHmac('sha1', base32(secret)).update(msg).digest();
  const o = (h[h.length - 1] ?? 0) & 15;
  const bin = (((h[o] ?? 0) & 0x7f) << 24) | ((h[o + 1] ?? 0) << 16) | ((h[o + 2] ?? 0) << 8) | (h[o + 3] ?? 0);
  return String(bin % 1_000_000).padStart(6, '0');
}

/**
 * Enrols the authenticator app on a Sign-in & security page: QR code shown, setup key read from "Can't scan the
 * code?", a computed code confirms it, recovery codes are acknowledged when they appear. Returns the setup key.
 */
export async function enrolAuthenticator(page: Page, email: string): Promise<string> {
  await page.getByTestId('mfa-totp').getByRole('button', { name: 'Set up' }).click();
  const dialog = page.getByRole('dialog', { name: 'Set up your authenticator app' });
  await expect(dialog.getByRole('img', { name: 'QR code to add AfroTransact to your authenticator app' })).toBeVisible();
  await dialog.getByText("Can't scan the code?").click();
  const key = dialog.getByTestId('totp-secret');
  await expect(key).toBeVisible();
  const secret = (await key.getAttribute('data-secret')) ?? '';
  expect(secret).toMatch(/^[A-Z2-7]{32}$/);
  await expect(key).toHaveText(secret.replace(/(.{4})(?=.)/g, '$1 '));
  await expect(dialog.getByText(`Account: ${email}`)).toBeVisible();
  await dialog.getByLabel('6-digit code').fill('000000');
  await dialog.getByRole('button', { name: 'Verify and turn on' }).click();
  await expect(dialog.getByText('That code is incorrect or expired.')).toBeVisible();
  await dialog.getByLabel('6-digit code').fill(totp(secret));
  await dialog.getByRole('button', { name: 'Verify and turn on' }).click();
  const codes = page.getByTestId('recovery-codes');
  const on = page.getByTestId('mfa-totp').getByText('On', { exact: true });
  // First method: recovery codes are shown once; otherwise the card turns On straight away.
  await expect(codes.or(on).first()).toBeVisible();
  if (await codes.isVisible()) {
    await expect(codes.getByRole('listitem')).toHaveCount(10);
    await expect(page.getByRole('button', { name: 'Done' })).toBeDisabled();
    await page.getByLabel("I've saved my recovery codes").check();
    await page.getByRole('button', { name: 'Done' }).click();
  }
  await expect(on).toBeVisible();
  return secret;
}
