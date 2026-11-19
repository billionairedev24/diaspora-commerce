import { getStaffDb, resetStaffDb, setMockPictureRules } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { PictureRulesView } from '@/components/settings/PictureRulesView';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

const NOW = new Date('2026-11-19T10:00:00Z');

describe('Picture rules: what is in force', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('shows the environment starting profile, nobody having changed it, and warns because it is not strict', async () => {
    await renderScreen(<PictureRulesView />);
    const warning = await screen.findByTestId('pr-warning');
    expect(warning).toHaveTextContent('Picture rules are relaxed');
    expect(warning).toHaveTextContent('Startup (relaxed)');
    const force = within(screen.getByTestId('pr-in-force'));
    expect(force.getByText('Startup (relaxed)')).toBeInTheDocument();
    expect(force.getByText('The starting profile of this environment (Startup (relaxed)); nobody has changed it yet.')).toBeInTheDocument();
    expect(force.getByText(/JPG, PNG, WebP and AVIF, in every profile/)).toBeInTheDocument();
    expect(await screen.findByText('Nobody has changed the picture rules yet.')).toBeInTheDocument();
  });

  it('names who saved it, when and why, never an id', async () => {
    setMockPictureRules(getStaffDb(), { profile: 'testing', updatedBy: 'Chidi Eze', reason: 'Unblock testing' });
    await renderScreen(<PictureRulesView />);
    const force = within(await screen.findByTestId('pr-in-force'));
    expect(force.getByText(/^Saved by Chidi Eze on .*“Unblock testing”\./)).toBeInTheDocument();
    expect(await screen.findByTestId('pr-warning')).toHaveTextContent('Products without any photo can be published.');
    expect(screen.getByTestId('pr-warning')).toHaveTextContent('shows products even without a real photo');
    const rows = await screen.findAllByTestId('pr-change');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('Chidi Eze');
    expect(rows[0]).toHaveTextContent('the environment’s starting profile to Testing (everything optional)');
    expect(rows[0]).toHaveTextContent('Unblock testing');
  });

  it('shows no warning, only a note, on the strict standard without changes', async () => {
    setMockPictureRules(getStaffDb(), { profile: 'strict', updatedBy: 'Chidi Eze', reason: 'Product asked for the standard' });
    await renderScreen(<PictureRulesView />);
    expect(await screen.findByTestId('pr-strict')).toHaveTextContent('The strict standard is in force');
    expect(screen.queryByTestId('pr-warning')).not.toBeInTheDocument();
  });

  it('warns again on strict when a single rule is overridden, and lists it', async () => {
    setMockPictureRules(getStaffDb(), { profile: 'strict', overrides: { minImages: 3 }, updatedBy: 'Chidi Eze', reason: 'Beta wants three photos' });
    await renderScreen(<PictureRulesView />);
    expect(await screen.findByTestId('pr-warning')).toBeInTheDocument();
    expect(within(screen.getByTestId('pr-in-force')).getByText('1 rule: Photos needed to publish')).toBeInTheDocument();
  });

  it('puts the three profiles side by side with the numbers of each', async () => {
    await renderScreen(<PictureRulesView />);
    const table = within(await screen.findByTestId('pr-compare'));
    expect(table.getByRole('columnheader', { name: /Strict \(the standard\)/ })).toBeInTheDocument();
    const photosRow = table.getByRole('row', { name: /Photos needed to publish/ });
    expect(within(photosRow).getAllByRole('cell').map((c) => c.textContent)).toEqual(['1 photo', '1 photo', 'none needed']);
    const sizeRow = table.getByRole('row', { name: /Main photo, longest side at least/ });
    expect(within(sizeRow).getAllByRole('cell').map((c) => c.textContent)).toEqual(['1000 px', '500 px', '1 px']);
  });

  it('is read-only for staff who can only moderate: no field, no button', async () => {
    asRoles('platform.product_owner');
    const { container } = await renderScreen(<PictureRulesView />);
    expect(await screen.findByTestId('pr-in-force')).toBeInTheDocument();
    expect(await screen.findByTestId('pr-read-only')).toHaveTextContent('You can look at the picture rules but not change them');
    expect(screen.queryByTestId('pr-editor')).not.toBeInTheDocument();
    expect(container.querySelector('input, select, textarea')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Review change' })).not.toBeInTheDocument();
  });
});

describe('Picture rules: change them', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles(null);
  });

  it('explains every rule in plain words behind an info button', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    const rule = within(await screen.findByTestId('pr-rule-allowPlaceholders'));
    await user.click(rule.getByRole('button', { name: 'About Count stand-in images as photos' }));
    expect(await screen.findByText(/Icons, emoji and SVG files are never accepted/)).toBeInTheDocument();
  });

  it('starts from the profile, lets one rule be overridden and put back', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    const rule = within(await screen.findByTestId('pr-rule-minImages'));
    const input = rule.getByRole('textbox', { name: 'Photos needed to publish' });
    expect(input).toHaveValue('1');
    expect(rule.getByText('Profile value: 1 photo')).toBeInTheDocument();
    expect(rule.queryByRole('button', { name: 'Back to profile' })).not.toBeInTheDocument();
    await user.clear(input);
    await user.type(input, '3');
    await user.click(rule.getByRole('button', { name: 'Back to profile' }));
    expect(rule.getByRole('textbox', { name: 'Photos needed to publish' })).toHaveValue('1');
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled();
  });

  it('checks the numbers before anything is asked', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    const review = await screen.findByRole('button', { name: 'Review change' });
    const size = within(screen.getByTestId('pr-rule-maxMegabytes')).getByRole('textbox', { name: 'Largest file, in MB' });
    await user.clear(size);
    await user.type(size, '99');
    expect(await screen.findByText('Use 1 to 20.')).toBeInTheDocument();
    expect(review).toBeDisabled();
    await user.clear(size);
    await user.type(size, '8');
    const short = within(screen.getByTestId('pr-rule-minShortSidePx')).getByRole('textbox', { name: 'Every photo, shortest side at least' });
    await user.clear(short);
    await user.type(short, '900');
    expect(await screen.findByText('The shortest side cannot be more than the longest side.')).toBeInTheDocument();
    expect(review).toBeDisabled();
  });

  it('asks for a reason, saves, and then shows the new profile, who set it and the change in the history', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    await user.click(await screen.findByRole('radio', { name: /Testing \(everything optional\)/ }));
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Change the picture rules?' }));
    expect(dialog.getByText('From Startup (relaxed) to Testing (everything optional). It applies to every seller and shopper within 30 seconds.')).toBeInTheDocument();
    expect(dialog.getByTestId('pr-dialog-relaxed')).toHaveTextContent('Products without any photo will be published.');
    await user.click(dialog.getByRole('button', { name: 'Save' }));
    expect(await dialog.findByText('Say why, at least 3 characters.')).toBeInTheDocument();
    expect((await mockApi().pictureRules.get()).rules.version).toBe(0);
    await user.type(dialog.getByRole('textbox', { name: /Why are you changing it/ }), 'Unblock testing');
    await user.click(dialog.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const force = within(screen.getByTestId('pr-in-force'));
    expect(await force.findByText('Testing (everything optional)')).toBeInTheDocument();
    expect(force.getByText(/^Saved by .* on .*“Unblock testing”\./)).toBeInTheDocument();
    const rows = await screen.findAllByTestId('pr-change');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('Testing (everything optional)');
    const saved = await mockApi().pictureRules.get();
    expect(saved.rules).toMatchObject({ profile: 'testing', version: 1 });
    expect(saved.rules.knobs.minImages).toBe(0);
  });

  it('saves a single override on the strict profile and lists it', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    await user.click(await screen.findByRole('radio', { name: /Strict \(the standard\)/ }));
    const input = within(screen.getByTestId('pr-rule-minLongSidePx')).getByRole('textbox', { name: 'Main photo, longest side at least' });
    await user.clear(input);
    await user.type(input, '800');
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    const dialog = within(await screen.findByRole('dialog'));
    await user.type(dialog.getByRole('textbox', { name: /Why are you changing it/ }), 'Beta wants smaller photos');
    await user.click(dialog.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const saved = await mockApi().pictureRules.get();
    expect(saved.rules).toMatchObject({ profile: 'strict', overridden: ['minLongSidePx'] });
    expect(saved.rules.knobs.minLongSidePx).toBe(800);
    expect(within(await screen.findByTestId('pr-in-force')).getByText('1 rule: Main photo, longest side at least')).toBeInTheDocument();
  });

  it('says so when someone else changed the rules in the meantime, and saves nothing', async () => {
    const user = userEvent.setup();
    await renderScreen(<PictureRulesView />);
    await user.click(await screen.findByRole('radio', { name: /Strict \(the standard\)/ }));
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    await mockApi().pictureRules.set({ profile: 'testing', overrides: {}, reason: 'Someone else' }, 0);
    const dialog = within(await screen.findByRole('dialog'));
    await user.type(dialog.getByRole('textbox', { name: /Why are you changing it/ }), 'Too late');
    await user.click(dialog.getByRole('button', { name: 'Save' }));
    expect(await dialog.findByRole('alert')).toHaveTextContent(/Someone else changed this since you loaded it/);
    expect((await mockApi().pictureRules.get()).rules.profile).toBe('testing');
  });

  it('is axe-clean, with the editor and the confirm step open', async () => {
    const user = userEvent.setup();
    const { container } = await renderScreen(<PictureRulesView />);
    await user.click(await screen.findByRole('radio', { name: /Testing \(everything optional\)/ }));
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    await screen.findByRole('dialog');
    expect((await axe.run(container, { rules: { 'color-contrast': { enabled: false } } })).violations).toEqual([]);
  });
});
