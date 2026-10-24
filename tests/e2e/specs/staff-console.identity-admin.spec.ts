import { expect, test } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../support/a11y';

/* Staff console › Identity & access (S291) against the in-process mock BFF (default role: platform super admin, who holds
   identity:admin). OAuth clients (register, secret shown once, rotate, disable), sessions and tokens (revoke), the signing
   key rotation, the token inspector and the settings, each change with a reason. Mock state lives in the app server
   process, so names are unique per run. */
test.describe('staff console · identity & access', () => {
  test('clients, a secret shown once, a revoked sign-in, key rotation, the token inspector @a11y', async ({ page }, testInfo) => {
    const run = Date.now().toString(36).slice(-6);
    const reason = 'End-to-end journey of the identity admin console';

    // Super Admin finds it under Settings (Platform), as well as at /identity.
    await page.goto('/settings');
    await page.getByRole('region', { name: 'Platform' }).getByRole('link', { name: /Identity & access/ }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Identity & access' })).toBeVisible();
    await page.goto('/identity');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Identity & access');
    const clients = page.getByRole('table', { name: 'OAuth clients' });
    await expect(clients.getByText('gateway-bff')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff identity clients' });

    // The platform's own client cannot be disabled.
    await clients.getByText('gateway-bff').click();
    await expect(page.getByRole('button', { name: /Disable client/ })).toBeDisabled();
    await page.keyboard.press('Escape');

    // Register a confidential client: a short reason is refused, the secret is shown once.
    await page.getByRole('button', { name: 'New' }).click();
    const form = page.getByRole('dialog', { name: 'Register an OAuth client' });
    await form.getByLabel('Client id').fill(`e2e-${run}`);
    await form.getByLabel('Name').fill(`E2E ${run}`);
    await form.getByLabel('Client credentials').check();
    await form.getByLabel(/^Reason/).fill('short');
    await form.getByRole('button', { name: 'Register' }).click();
    await expect(form.getByText('Give a reason of 10 to 500 characters').first()).toBeVisible();
    await form.getByLabel(/^Reason/).fill(reason);
    await form.getByRole('button', { name: 'Register' }).click();
    const secretDialog = page.getByRole('dialog', { name: 'Client secret' });
    const secret = await secretDialog.getByLabel('Client secret').inputValue();
    expect(secret.length).toBeGreaterThan(20);
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff identity secret shown once' });
    await secretDialog.getByRole('button', { name: 'I have copied it' }).click();
    await expect(secretDialog).toBeHidden();
    await expect(page.getByText(secret)).toHaveCount(0);

    // Rotate it, then disable and enable it.
    await page.getByRole('button', { name: 'Rotate secret' }).click();
    const rotate = page.getByRole('dialog', { name: 'Rotate the client secret' });
    await rotate.getByLabel(/^Reason/).fill(reason);
    await rotate.getByRole('button', { name: 'Rotate secret' }).click();
    const rotated = page.getByRole('dialog', { name: 'New client secret' });
    await expect(rotated.getByText(/previous secret works for 24 hours/)).toBeVisible();
    await rotated.getByRole('button', { name: 'I have copied it' }).click();
    await page.getByRole('button', { name: 'Disable client' }).click();
    const disable = page.getByRole('dialog', { name: 'Disable the client' });
    await disable.getByLabel(/^Reason/).fill(reason);
    await disable.getByRole('button', { name: 'Disable' }).click();
    await expect(page.getByRole('button', { name: 'Enable client' })).toBeEnabled();
    await page.keyboard.press('Escape');

    // Sessions & tokens: revoke one sign-in with a reason.
    await page.getByRole('tab', { name: 'Sessions & tokens' }).click();
    const sessions = page.getByRole('table', { name: 'Sessions & tokens' });
    await expect(sessions.getByText('amara@example.com').first()).toBeVisible();
    await sessions.getByText('amara@example.com').first().click();
    await page.getByRole('button', { name: 'Revoke this sign-in' }).click();
    const revoke = page.getByRole('dialog', { name: 'Revoke this sign-in' });
    await revoke.getByLabel(/^Reason/).fill(reason);
    await revoke.getByRole('button', { name: 'Revoke' }).click();
    await expect(revoke).toBeHidden();

    // Signing keys: rotate; the new key is active and the previous one keeps publishing while it retires.
    await page.getByRole('tab', { name: 'Signing keys' }).click();
    const keys = page.getByRole('table', { name: 'Signing keys' });
    await expect(keys.getByText('Retiring')).toBeVisible();
    await page.getByRole('button', { name: 'New' }).click();
    const rotateKey = page.getByRole('dialog', { name: 'Rotate the signing key' });
    await rotateKey.getByLabel(/^Reason/).fill(reason);
    await rotateKey.getByRole('button', { name: 'Rotate' }).click();
    await expect(rotateKey).toBeHidden();
    await expect(keys.getByText('Active')).toHaveCount(1);

    // Token inspector: a token the mock signed is valid, a forged one is not; the token is never shown back.
    await page.getByRole('tab', { name: 'Token inspector' }).click();
    const exp = Math.floor(Date.now() / 1000) + 300;
    const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const token = `${b64({ alg: 'RS256', kid: 'k20260915T090000-3fa1' })}.${b64({ iss: 'https://id.afrotransact.com', sub: 'user-1', scope: 'openid', exp })}.bW9jay1zaWduYXR1cmU`;
    await page.getByLabel('Token (JWT)').fill(token);
    await page.getByRole('button', { name: 'Inspect token' }).click();
    const result = page.getByRole('region', { name: 'Inspection result' });
    await expect(result.getByText('Valid.')).toBeVisible();
    await expectNoSeriousA11yViolations(page, testInfo, { page: 'staff identity token inspector' });
    await page.getByLabel('Token (JWT)').fill(`${token.slice(0, -4)}AAAA`);
    await page.getByRole('button', { name: 'Inspect token' }).click();
    await expect(result.getByText('Not valid.')).toBeVisible();
    await page.getByRole('button', { name: 'Clear' }).click();
    await expect(page.getByLabel('Token (JWT)')).toHaveValue('');
    await expect(page.getByText('OIDC discovery document')).toBeVisible();

    // Settings: a valid change is saved with a reason; an invalid one is refused before it is sent.
    await page.getByRole('tab', { name: 'Settings' }).click();
    await page.getByRole('table', { name: 'Settings' }).getByText('Sign-in, password and token settings').click();
    await page.getByRole('button', { name: 'Edit' }).click();
    const settings = page.getByRole('dialog', { name: 'Change the settings' });
    await settings.getByLabel('Shortest password (characters)').fill('4');
    await settings.getByLabel(/^Reason/).fill(reason);
    await settings.getByRole('button', { name: 'Save settings' }).click();
    await expect(settings.getByText('Must be at least 8').first()).toBeVisible();
    await settings.getByLabel('Shortest password (characters)').fill('14');
    await settings.getByRole('button', { name: 'Save settings' }).click();
    await expect(settings).toBeHidden();
    await expect(page.getByText('Shortest password (characters)')).toBeVisible();
  });

  test('is not in the sidebar of staff without identity:admin', async ({ page, context, baseURL }) => {
    await context.addCookies([{ name: 'at_mock_staff_role', value: 'platform.developer', url: baseURL ?? 'http://localhost:3412' }]);
    await page.goto('/settings');
    const sections = page.getByRole('region', { name: 'Platform' });
    await expect(sections.getByRole('link', { name: /Sign-in & integrations/ })).toBeVisible();
    await expect(sections.getByRole('link', { name: /Identity & access/ })).toHaveCount(0);
    await page.goto('/identity');
    await expect(page.getByText(/can.t open identity/i)).toBeVisible();
  });
});
