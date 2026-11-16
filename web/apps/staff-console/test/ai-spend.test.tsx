import { getStaffDb, resetStaffDb, setMockAiCap, setMockAiSpend } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AiSpendView } from '@/components/settings/AiSpendView';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

const NOW = new Date('2026-11-16T10:00:00Z');

describe('AI and MCP spend cap: today', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('shows what has been spent today against the cap, by feature, from the ledger', async () => {
    await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 1.40 of USD 2.00 used (70%)');
    const meter = screen.getByRole('progressbar', { name: "70% of today's limit used" });
    expect(meter).toHaveAttribute('value', '1400000');
    const list = within(screen.getByTestId('ai-by-feature'));
    expect(list.getByText('Lola')).toBeInTheDocument();
    expect(list.getByText('Listing writer')).toBeInTheDocument();
    expect(list.getByText('USD 1.25')).toBeInTheDocument();
    expect(screen.getByText(/15 AI calls and 11 000 tokens|15 AI calls and 11,000 tokens/)).toBeInTheDocument();
    expect(screen.getByText(/The day ends at 00:00 UTC/)).toBeInTheDocument();
  });

  it('says nothing was recorded, with a spend of zero, when no AI call was made today', async () => {
    setMockAiSpend(getStaffDb(), []);
    await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 0.00 of USD 2.00 used (0%)');
    expect(screen.getByText(/No AI call has been recorded today, so the spend is zero\./)).toBeInTheDocument();
    expect(screen.queryByTestId('ai-by-feature')).not.toBeInTheDocument();
  });

  it('shows a small spend below a cent as such', async () => {
    setMockAiSpend(getStaffDb(), [{ feature: 'translation', label: 'Translation', calls: 1, tokens: 120, costMicros: 4_200 }]);
    await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 0.0042 of USD 2.00');
  });

  it('says AI is switched off, with no meter, when the cap is 0', async () => {
    setMockAiCap(getStaffDb(), { capMicros: 0, changedByName: 'Chidi Eze', reason: 'Pause AI' });
    await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 1.40 used (AI is switched off)');
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    const force = within(screen.getByTestId('ai-spend'));
    expect(await force.findByText('AI is switched off (the limit is USD 0.00).')).toBeInTheDocument();
    expect(force.getByText(/Nothing is left today: AI calls are stopped until 00:00 UTC/)).toBeInTheDocument();
  });
});

describe('AI and MCP spend cap: what is in force', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('shows the cap, where it comes from, the ceiling and what is left today, and no change history yet', async () => {
    await renderScreen(<AiSpendView />);
    const force = within(await screen.findByTestId('ai-spend'));
    expect(force.getByText('USD 2.00 per UTC day (AI features, Lola and the MCP server share it).')).toBeInTheDocument();
    expect(force.getByText('The starting value set on the deployment (USD 2.00); nobody has changed it yet.')).toBeInTheDocument();
    expect(force.getByText(/^USD 20\.00, set on the deployment so a mistake cannot cost more/)).toBeInTheDocument();
    expect(force.getByText('USD 0.60 until the day ends at 00:00 UTC.')).toBeInTheDocument();
    expect(await screen.findByText(/Nobody has changed the limit yet/)).toBeInTheDocument();
    // environment variable names are for engineers, not for the page
    expect(screen.queryByText(/AI_GLOBAL_DAILY_COST/)).not.toBeInTheDocument();
  });

  it('names who saved the cap, when and why, and shows the history by name', async () => {
    setMockAiCap(getStaffDb(), { capMicros: 5_000_000, changedByName: 'Chidi Eze', reason: 'Launch week' });
    await renderScreen(<AiSpendView />);
    const force = within(await screen.findByTestId('ai-spend'));
    expect(await force.findByText(/^Set by Chidi Eze on .*“Launch week”\./)).toBeInTheDocument();
    const rows = await screen.findAllByTestId('ai-cap-change');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('Chidi Eze');
    expect(rows[0]).toHaveTextContent('USD 2.00 (starting value) to USD 5.00');
    expect(rows[0]).toHaveTextContent('Launch week');
  });

  it('is read-only text for staff without the cap permission: no field, no button', async () => {
    asRoles('platform.product_owner');
    const { container } = await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spend')).toBeInTheDocument();
    expect(await screen.findByTestId('ai-cap-read-only')).toHaveTextContent('You can look but not change');
    expect(screen.queryByTestId('ai-cap-editor')).not.toBeInTheDocument();
    expect(container.querySelector('input, select, textarea, [role="switch"]')).toBeNull();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('AI and MCP spend cap: change it', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  const field = async () => screen.findByRole('textbox', { name: 'New daily limit (USD)' });

  it('checks the amount before anything is asked: dollars, whole cents, at most the ceiling, and a real change', async () => {
    const user = userEvent.setup();
    await renderScreen(<AiSpendView />);
    const input = await field();
    const review = screen.getByRole('button', { name: 'Review the change' });
    expect(input).toHaveValue('2.00');
    expect(review).toBeDisabled();
    await user.clear(input);
    await user.type(input, 'lots');
    expect(await screen.findByText('Enter an amount in dollars, like 5 or 5.50')).toBeInTheDocument();
    expect(review).toBeDisabled();
    await user.clear(input);
    await user.type(input, '1.234');
    expect(screen.getByText('Enter an amount in dollars, like 5 or 5.50')).toBeInTheDocument();
    await user.clear(input);
    await user.type(input, '25');
    expect(screen.getByText('The most you can set is USD 20.00')).toBeInTheDocument();
    expect(review).toBeDisabled();
    await user.clear(input);
    await user.type(input, '2');
    expect(screen.getByText('That is the limit already in force')).toBeInTheDocument();
    expect(review).toBeDisabled();
    await user.clear(input);
    await user.type(input, '$5.5');
    expect(review).toBeEnabled();
  });

  it('asks for a reason, saves, and then shows the new cap, who set it and the change in the history', async () => {
    const user = userEvent.setup();
    await renderScreen(<AiSpendView />);
    const input = await field();
    await user.clear(input);
    await user.type(input, '5.50');
    await user.click(screen.getByRole('button', { name: 'Review the change' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Change the daily AI limit' }));
    expect(dialog.getByText('From USD 2.00 to USD 5.50 per UTC day. It takes effect within seconds and applies to today.')).toBeInTheDocument();
    // no reason: nothing is saved
    await user.click(dialog.getByRole('button', { name: 'Change the limit' }));
    expect(await dialog.findByText('Say why, at least 3 characters: it is kept with your name')).toBeInTheDocument();
    expect((await mockApi().aiSpend.cap()).version).toBe(0);
    await user.type(dialog.getByRole('textbox', { name: 'Why are you changing it? (3 to 300 characters)' }), 'Diaspora week promotion');
    await user.click(dialog.getByRole('button', { name: 'Change the limit' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const force = within(screen.getByTestId('ai-spend'));
    expect(await force.findByText('USD 5.50 per UTC day (AI features, Lola and the MCP server share it).')).toBeInTheDocument();
    expect(force.getByText(/^Set by .* on .*“Diaspora week promotion”\./)).toBeInTheDocument();
    expect(force.getByText('USD 4.10 until the day ends at 00:00 UTC.')).toBeInTheDocument();
    const rows = await screen.findAllByTestId('ai-cap-change');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('USD 2.00 (starting value) to USD 5.50');
    expect(rows[0]).toHaveTextContent('Diaspora week promotion');
    // the saved value is the one the server holds, with its version
    const saved = await mockApi().aiSpend.cap();
    expect(saved).toMatchObject({ capMicros: 5_500_000, source: 'saved', version: 1 });
    expect(await field()).toHaveValue('5.50');
  });

  it('warns before lowering the cap below what has been spent, and before switching AI off', async () => {
    const user = userEvent.setup();
    await renderScreen(<AiSpendView />);
    const input = await field();
    await user.clear(input);
    await user.type(input, '1');
    await user.click(screen.getByRole('button', { name: 'Review the change' }));
    expect(await screen.findByTestId('ai-warn-below')).toHaveTextContent('Today’s spend (USD 1.40) is already above this limit');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await user.clear(input);
    await user.type(input, '0');
    await user.click(screen.getByRole('button', { name: 'Review the change' }));
    expect(await screen.findByTestId('ai-warn-off')).toHaveTextContent('switches AI off for everyone');
  });

  it('says so when someone else changed the cap in the meantime, and saves nothing', async () => {
    const user = userEvent.setup();
    await renderScreen(<AiSpendView />);
    const input = await field();
    await user.clear(input);
    await user.type(input, '4');
    await user.click(screen.getByRole('button', { name: 'Review the change' }));
    // another person saves first (version 0 -> 1)
    await mockApi().aiSpend.setCap({ capMicros: 3_000_000, reason: 'Someone else' }, 0);
    const dialog = within(await screen.findByRole('dialog'));
    await user.type(dialog.getByRole('textbox', { name: /Why are you changing it/ }), 'Too late');
    await user.click(dialog.getByRole('button', { name: 'Change the limit' }));
    expect(await dialog.findByRole('alert')).toHaveTextContent(/Someone else changed this since you loaded it/);
    expect((await mockApi().aiSpend.cap()).capMicros).toBe(3_000_000);
  });

  it('is axe-clean, with the form and the confirm step open', async () => {
    const user = userEvent.setup();
    const { container } = await renderScreen(<AiSpendView />);
    const input = await field();
    await user.clear(input);
    await user.type(input, '3');
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations.map((v) => v.id)).toEqual([]);
    await user.click(screen.getByRole('button', { name: 'Review the change' }));
    const dialog = await screen.findByRole('dialog');
    expect((await axe.run(dialog, { rules: { 'color-contrast': { enabled: false } } })).violations.map((v) => v.id)).toEqual([]);
  });
});

describe('the AI spend cap API (mock of the BFF)', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('refuses a change without If-Match or the permission, above the ceiling, and with a bad reason', async () => {
    const api = mockApi().aiSpend;
    await expect(api.setCap({ capMicros: 1_000_000, reason: 'ok!' }, 7)).rejects.toMatchObject({ status: 412 });
    await expect(api.setCap({ capMicros: 25_000_000, reason: 'Way too much' }, 0)).rejects.toMatchObject({ status: 422 });
    await expect(api.setCap({ capMicros: 1_000_000, reason: 'no' }, 0)).rejects.toMatchObject({ status: 422 });
    expect((await api.cap()).source).toBe('default');
    asRoles('platform.product_owner');
    await expect(mockApi().aiSpend.setCap({ capMicros: 1_000_000, reason: 'Not allowed' }, 0)).rejects.toMatchObject({ status: 403 });
    asRoles('platform.customer_support');
    await expect(mockApi().aiSpend.cap()).rejects.toMatchObject({ status: 403 });
  });
});
