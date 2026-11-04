import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FormFields } from '@/components/resources/FormFields';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';

/* Placement editor: home tile cells can be a product rule instead of a fixed image, hero slides can carry a schedule.
   The tile and hero fields appear only for their slot. */

beforeEach(() => {
  resetStaffDb(new Date());
  asRoles(null);
});

async function createForm() {
  const page = await mockApi().listResource('placements', { status: '', q: '', page: 1, size: 25, sort: [] });
  if (!page.createForm) throw new Error('no create form');
  return page.createForm;
}

describe('placement editor', () => {
  it('offers tile rules and slide schedule fields, each only for its slot', async () => {
    const user = userEvent.setup();
    await renderScreen(<FormFields form={await createForm()} onSubmit={vi.fn()} />);
    expect(screen.queryByLabelText(/Product rule/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Show the slide from/)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/^Slot/), 'home_tiles');
    expect(screen.getAllByLabelText(/Product rule/)).toHaveLength(4);
    expect(screen.getByRole('heading', { name: 'Tile cell 1 (home tiles only)' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Show the slide from/)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText(/^Slot/), 'hero_carousel');
    expect(screen.queryByLabelText(/Product rule/)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Show the slide from/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Hide the slide after/)).toBeInTheDocument();
  });

  it('sends the chosen rule and leaves hidden slot fields out', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    await renderScreen(<FormFields form={await createForm()} onSubmit={onSubmit} />);
    await user.type(screen.getByLabelText(/^Name/), 'Autumn picks');
    await user.selectOptions(screen.getByLabelText(/^Slot/), 'home_tiles');
    await user.type(screen.getByLabelText(/^Headline/), 'Top rated');
    fireEvent.change(screen.getByLabelText(/^Starts/), { target: { value: '2026-11-04T09:00' } });
    const rules = screen.getAllByLabelText(/Product rule/);
    await user.selectOptions(rules[0]!, 'top-rated');
    await user.selectOptions(screen.getAllByLabelText(/Department \(for department rules\)/)[0]!, 'Fashion');
    await user.click(screen.getByRole('button', { name: 'Create draft' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const sent = onSubmit.mock.calls[0]![0] as Record<string, unknown>;
    expect(sent).toMatchObject({ slot: 'home_tiles', cell1_rule: 'top-rated', cell1_department: 'Fashion' });
    expect(Object.keys(sent).some((k) => k.startsWith('slide'))).toBe(false);
  });
});
