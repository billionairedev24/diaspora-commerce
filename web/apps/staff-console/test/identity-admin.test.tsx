import { MOCK_VALID_SIGNATURE, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { IdentityAdminView } from '@/components/identity/IdentityAdminView';
import { moduleOf } from '@/lib/modules';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const REASON = 'Quarterly security review';

describe('Developer tools › Identity & access (S291)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/identity');
  });

  it('is its own module, in the sidebar for identity:admin only', async () => {
    expect(moduleOf('/identity')).toBe('identity');
    const admin = (await mockApi().getSession()).navGroups.flatMap((g) => g.modules.map((m) => m.key));
    expect(admin).toContain('identity');
    asRoles('platform.developer');
    const developer = (await mockApi().getSession()).navGroups.flatMap((g) => g.modules.map((m) => m.key));
    expect(developer).not.toContain('identity');
  });

  it('lists the clients, with the platform’s own client locked against disabling', async () => {
    const user = userEvent.setup();
    await renderScreen(<IdentityAdminView />);
    expect(await screen.findByRole('heading', { name: 'Identity & access' })).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'OAuth clients' });
    expect(await within(table).findByText('gateway-bff')).toBeInTheDocument();
    expect(within(table).getByText('media-service')).toBeInTheDocument();
    await user.click(within(table).getByText('gateway-bff'));
    expect(await screen.findByRole('button', { name: /Disable client/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Disable client/ })).toHaveAttribute('title', expect.stringMatching(/lock every staff member out/));
  });

  it('registers a client, shows its secret once, and the sheet never shows it again', async () => {
    const user = userEvent.setup();
    await renderScreen(<IdentityAdminView />);
    await user.click(await screen.findByRole('button', { name: 'New' }));
    const form = await screen.findByRole('dialog', { name: 'Register an OAuth client' });
    await user.type(within(form).getByLabelText('Client id'), 'partner-api');
    await user.type(within(form).getByLabelText('Name'), 'Partner API');
    await user.click(within(form).getByLabelText('Client credentials'));
    await user.clear(within(form).getByLabelText(/^Access token lifetime/));
    await user.type(within(form).getByLabelText(/^Access token lifetime/), '5');
    await user.type(within(form).getByLabelText(/^Reason/), 'short');
    await user.click(within(form).getByRole('button', { name: 'Register' }));
    // identity wants 10 to 500 characters: the form says so before anything is sent
    expect((await within(form).findAllByText('Give a reason of 10 to 500 characters')).length).toBeGreaterThan(0);
    await user.clear(within(form).getByLabelText(/^Reason/));
    await user.type(within(form).getByLabelText(/^Reason/), REASON);
    await user.click(within(form).getByRole('button', { name: 'Register' }));

    const secretDialog = await screen.findByRole('dialog', { name: 'Client secret' });
    const secret = (within(secretDialog).getByLabelText('Client secret') as HTMLInputElement).value;
    expect(secret.length).toBeGreaterThan(20);
    expect(within(secretDialog).getByText('Shown once.')).toBeInTheDocument();
    await user.click(within(secretDialog).getByRole('button', { name: 'I have copied it' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Client secret' })).not.toBeInTheDocument());
    expect(document.body.textContent).not.toContain(secret);
  });

  it('rotates a secret after a reason and shows the new one once', async () => {
    const user = userEvent.setup();
    nav.set('/identity?tab=oauth-clients&open=inventory-service');
    await renderScreen(<IdentityAdminView />);
    await user.click(await screen.findByRole('button', { name: 'Rotate secret' }));
    const dialog = await screen.findByRole('dialog', { name: 'Rotate the client secret' });
    await user.type(within(dialog).getByLabelText(/^Reason/), REASON);
    await user.click(within(dialog).getByRole('button', { name: 'Rotate secret' }));
    const secretDialog = await screen.findByRole('dialog', { name: 'New client secret' });
    expect((within(secretDialog).getByLabelText('New client secret') as HTMLInputElement).value).toMatch(/^mock-/);
    expect(within(secretDialog).getByText(/previous secret works for 24 hours/)).toBeInTheDocument();
  });

  it('inspects a pasted token and clears it', async () => {
    const user = userEvent.setup();
    nav.set('/identity?tab=inspector');
    await renderScreen(<IdentityAdminView />);
    const exp = Math.floor(Date.now() / 1000) + 300;
    const good = `${b64({ alg: 'RS256', kid: 'k20260915T090000-3fa1' })}.${b64({ iss: 'https://id.afrotransact.com', sub: 'user-1', scope: 'openid profile', exp })}.${MOCK_VALID_SIGNATURE}`;
    const box = await screen.findByLabelText('Token (JWT)');
    await user.click(box);
    await user.paste(good);
    await user.click(screen.getByRole('button', { name: 'Inspect token' }));
    const result = await screen.findByRole('region', { name: 'Inspection result' });
    expect(await within(result).findByText('Valid.')).toBeInTheDocument();
    expect(within(result).getByText('openid, profile')).toBeInTheDocument();
    expect(within(result).getByText('k20260915T090000-3fa1')).toBeInTheDocument();
    expect(result.textContent).not.toContain(good);
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    expect((screen.getByLabelText('Token (JWT)') as HTMLTextAreaElement).value).toBe('');
    expect(screen.queryByRole('region', { name: 'Inspection result' })).not.toBeInTheDocument();

    await user.click(box);
    await user.paste('not-a-token');
    await user.click(screen.getByRole('button', { name: 'Inspect token' }));
    expect(await screen.findByText('This does not look like a signed JWT.')).toBeInTheDocument();
  });

  it('shows the published documents', async () => {
    nav.set('/identity?tab=inspector');
    await renderScreen(<IdentityAdminView />);
    const discovery = await screen.findByText('OIDC discovery document');
    expect(discovery).toBeInTheDocument();
    expect(await screen.findByText('JWKS (public keys)')).toBeInTheDocument();
    await waitFor(() => expect(document.body.textContent).toContain('"jwks_uri"'));
  });
});
