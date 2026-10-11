import { type AccountSecurity } from '@afrotransact/api-client';
import { checkCatalogs } from '@afrotransact/i18n/testing';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import jsQR from 'jsqr';
import { describe, expect, it, vi } from 'vitest';

import { ACCOUNT_CATALOGS, enrolmentReducer, groupKey, initialEnrolment, recoveryFile, TotpEnrolmentDialog, TwoStepCard } from '../src';
import { fakeApi, renderWith } from './helpers';

const SECRET = 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP';
const URI = `otpauth://totp/AfroTransact:ops.lead%40afrotransact.com?secret=${SECRET}&issuer=AfroTransact&algorithm=SHA1&digits=6&period=30`;

/** Reads the rendered QR SVG back (1×1 module squares → pixels → jsQR). */
function decodeQr(svg: Element): string | null {
  const box = Number(svg.getAttribute('viewBox')?.split(' ')[2]);
  const scale = 5;
  const px = box * scale;
  const rgba = new Uint8ClampedArray(px * px * 4).fill(255);
  for (const m of (svg.querySelector('path')?.getAttribute('d') ?? '').matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    for (let y = Number(m[2]) * scale; y < (Number(m[2]) + 1) * scale; y++) {
      for (let x = Number(m[1]) * scale; x < (Number(m[1]) + 1) * scale; x++) {
        const i = (y * px + x) * 4;
        rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
      }
    }
  }
  return jsQR(rgba, px, px)?.data ?? null;
}

describe('enrolment state machine', () => {
  it('walks start → scan → verify → codes → done and returns to scan on a wrong code', () => {
    let s = enrolmentReducer(initialEnrolment, { type: 'start' });
    expect(s.step).toBe('starting');
    expect(enrolmentReducer(s, { type: 'submit' }).step).toBe('starting');
    s = enrolmentReducer(s, { type: 'started', setup: { secret: SECRET, otpauthUri: URI } });
    expect(s.step).toBe('scan');
    s = enrolmentReducer(s, { type: 'submit' });
    expect(s.step).toBe('verifying');
    s = enrolmentReducer(s, { type: 'rejected', error: 'That code is incorrect' });
    expect(s).toMatchObject({ step: 'scan', error: 'That code is incorrect' });
    s = enrolmentReducer(enrolmentReducer(s, { type: 'submit' }), { type: 'confirmed', codes: ['a', 'b'] });
    expect(s.step).toBe('codes');
    expect(enrolmentReducer(s, { type: 'saved' }).step).toBe('done');
    const kept = enrolmentReducer(enrolmentReducer(enrolmentReducer(initialEnrolment, { type: 'start' }), { type: 'started', setup: { secret: SECRET, otpauthUri: URI } }), { type: 'submit' });
    expect(enrolmentReducer(kept, { type: 'confirmed', codes: [] }).step).toBe('done');
    expect(enrolmentReducer(initialEnrolment, { type: 'startFailed', error: 'x' }).step).toBe('failed');
  });

  it('groups the setup key and writes the recovery file', () => {
    expect(groupKey('JBSWY3DPEHPK3PXP')).toBe('JBSW Y3DP EHPK 3PXP');
    expect(recoveryFile('Codes', ['aaaaa-bbbbb', 'ccccc-ddddd'])).toBe('Codes\n\n 1. aaaaa-bbbbb\n 2. ccccc-ddddd\n');
  });
});

describe('authenticator enrolment dialog', () => {
  it('shows a QR code of the exact otpauth URI and the grouped key, confirms a code and shows recovery codes once', async () => {
    const user = userEvent.setup();
    const confirmTotp = vi.fn(async (code: string) => {
      if (code !== '123456') throw new Error('bad');
      return { recoveryCodes: ['k7q2m-9xdpw', 'p4wz3-hnac8'] };
    });
    const onClose = vi.fn();
    renderWith(<TotpEnrolmentDialog open email="ops.lead@afrotransact.com" onClose={onClose} />, fakeApi({ startTotp: vi.fn(async () => ({ secret: SECRET, otpauthUri: URI })), confirmTotp }));

    const qr = await screen.findByRole('img', { name: 'QR code to add AfroTransact to your authenticator app' });
    expect(decodeQr(qr)).toBe(URI);
    expect(screen.getByTestId('totp-secret')).toHaveTextContent('JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP');

    const code = screen.getByLabelText('6-digit code');
    await user.type(code, '12a3');
    expect(code).toHaveValue('123');
    await user.click(screen.getByRole('button', { name: 'Verify and turn on' }));
    expect(await screen.findByText('Enter the 6 digits from your app')).toBeInTheDocument();
    expect(confirmTotp).not.toHaveBeenCalled();

    await user.clear(code);
    await user.type(code, '999999');
    await user.click(screen.getByRole('button', { name: 'Verify and turn on' }));
    expect(await screen.findByText('That didn’t work. Please try again.'.replace('’', "'"))).toBeInTheDocument();

    await user.type(screen.getByLabelText('6-digit code'), '123456');
    await user.click(screen.getByRole('button', { name: 'Verify and turn on' }));
    const panel = await screen.findByTestId('recovery-codes');
    expect(within(panel).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['k7q2m-9xdpw', 'p4wz3-hnac8']);
    const done = screen.getByRole('button', { name: 'Done' });
    expect(done).toBeDisabled();
    await user.click(screen.getByLabelText("I've saved my recovery codes"));
    await user.click(done);
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});

const base: AccountSecurity = {
  mfa: { totp: true, sms: false, smsPhone: null, recoveryCodesLeft: 9, required: true, mayDisable: false, mayRemoveTotp: false, mayRemoveSms: false },
  passkeys: [],
  trustedBrowsers: [],
  sessions: [],
  devices: [],
  linkedAccounts: { accounts: [], hasPassword: true },
  passkeyRegistrationUrl: 'http://identity/webauthn/register'
};

describe('two-step verification card', () => {
  it('keeps staff on at least one method: the only method cannot be turned off', () => {
    renderWith(<TwoStepCard security={base} email="ops@afrotransact.com" phone="+17135550142" />, fakeApi({}));
    expect(screen.getByText('Required')).toBeInTheDocument();
    expect(within(screen.getByTestId('mfa-totp')).getByRole('button', { name: 'Turn off' })).toBeDisabled();
    expect(within(screen.getByTestId('mfa-sms')).getByRole('button', { name: 'Set up' })).toBeEnabled();
    expect(screen.getByTestId('two-step')).toHaveTextContent('required for staff accounts');
  });

  it('lets staff switch once both methods are on, and customers turn methods off', async () => {
    const user = userEvent.setup();
    const disableTotp = vi.fn(async () => undefined);
    renderWith(<TwoStepCard security={{ ...base, mfa: { ...base.mfa, sms: true, smsPhone: '•••• 0142', mayRemoveTotp: true, mayRemoveSms: true } }} email="ops@afrotransact.com" phone="+17135550142" />, fakeApi({ disableTotp }));
    await user.click(within(screen.getByTestId('mfa-totp')).getByRole('button', { name: 'Turn off' }));
    expect(await screen.findByText('You’ll sign in with your other method from now on.'.replace('’', "'"))).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Turn off' }));
    await waitFor(() => expect(disableTotp).toHaveBeenCalledTimes(1));
  });
});

describe('account catalogues', () => {
  it('every locale has every key and the same placeholders', () => {
    expect(checkCatalogs(ACCOUNT_CATALOGS)).toEqual([]);
  });
});
