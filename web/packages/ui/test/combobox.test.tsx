import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { Combobox, type ComboboxOption } from '../src';

const OPTIONS: ComboboxOption[] = [
  { value: 'rice', label: 'Rice, grains & flours', description: 'Groceries › Pantry › Rice, grains & flours', text: 'Groceries › Pantry › Rice, grains & flours' },
  { value: 'spices', label: 'Spices & seasonings', description: 'Groceries › Pantry › Spices & seasonings', text: 'Groceries › Pantry › Spices & seasonings' },
  { value: 'dresses', label: 'Dresses', description: 'Fashion › Women › Dresses', text: 'Fashion › Women › Dresses' },
  { value: 'butters', label: 'Butters & oils', description: 'Beauty › Skin & body › Butters & oils', text: 'Beauty › Skin & body › Butters & oils' }
];

function Harness({ onCreate, recent, popular, clearable }: { onCreate?: (t: string) => void; recent?: string[]; popular?: string[]; clearable?: boolean }) {
  const [value, setValue] = useState('');
  return (
    <>
      <Combobox label="Category" options={OPTIONS} value={value} onChange={setValue} recent={recent} popular={popular} onCreate={onCreate ? (t) => { onCreate(t); setValue(t); } : undefined} clearable={clearable} />
      <span data-testid="value">{value}</span>
    </>
  );
}

describe('Combobox', () => {
  it('follows the ARIA combobox pattern', async () => {
    render(<Harness />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveAttribute('aria-autocomplete', 'list');
    await userEvent.click(input);
    expect(input).toHaveAttribute('aria-expanded', 'true');
    const list = screen.getByRole('listbox');
    expect(input).toHaveAttribute('aria-controls', list.id);
    expect(within(list).getAllByRole('option')).toHaveLength(4);
    expect(input.getAttribute('aria-activedescendant')).toBe(within(list).getAllByRole('option')[0]?.id);
  });

  it('finds an option from any word of its full path', async () => {
    render(<Harness />);
    await userEvent.type(screen.getByRole('combobox', { name: 'Category' }), 'fashion dress');
    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveTextContent('Fashion › Women › Dresses');
    expect(screen.getByRole('status')).toHaveTextContent('1 results');
  });

  it('is accent- and case-insensitive and says when nothing matches', async () => {
    render(<Harness />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    await userEvent.type(input, 'SPÏCES');
    expect(screen.getAllByRole('option')).toHaveLength(1);
    await userEvent.clear(input);
    await userEvent.type(input, 'zzz');
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('moves with the arrow keys, chooses with Enter and shows the chosen path', async () => {
    render(<Harness />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    await userEvent.type(input, 'pantry');
    await userEvent.keyboard('{ArrowDown}');
    const [first, second] = screen.getAllByRole('option');
    expect(input.getAttribute('aria-activedescendant')).toBe(second?.id);
    expect(first).not.toHaveAttribute('data-active', 'true');
    await userEvent.keyboard('{ArrowUp}{ArrowUp}{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('rice');
    expect(input).toHaveValue('Groceries › Pantry › Rice, grains & flours');
    expect(input).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens with ArrowDown, closes with Escape without changing the value, and Tab leaves', async () => {
    render(<Harness />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    await userEvent.tab();
    expect(input).toHaveFocus();
    await userEvent.keyboard('{Escape}');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    await userEvent.keyboard('{ArrowDown}');
    expect(input).toHaveAttribute('aria-expanded', 'true');
    await userEvent.keyboard('{ArrowDown}{Escape}');
    expect(screen.getByTestId('value')).toHaveTextContent('');
    await userEvent.type(input, 'dress');
    await userEvent.tab();
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('value')).toHaveTextContent('');
    expect(input).toHaveValue('');
  });

  it('chooses with the pointer', async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole('combobox', { name: 'Category' }));
    await userEvent.click(screen.getByRole('option', { name: /Dresses/ }));
    expect(screen.getByTestId('value')).toHaveTextContent('dresses');
  });

  it('lists recent then popular values first while nothing is typed, then everything else', async () => {
    render(<Harness recent={['dresses']} popular={['butters', 'dresses']} />);
    await userEvent.click(screen.getByRole('combobox', { name: 'Category' }));
    const items = within(screen.getByRole('listbox')).getAllByRole('presentation').map((n) => n.textContent);
    expect(items).toEqual(['Recent', 'Popular', 'All']);
    const names = screen.getAllByRole('option').map((o) => o.textContent);
    expect(names[0]).toContain('Dresses');
    expect(names[1]).toContain('Butters & oils');
    expect(names).toHaveLength(4);
    // Typing replaces the groups with ranked matches.
    await userEvent.keyboard('oil');
    expect(screen.queryByText('Recent')).not.toBeInTheDocument();
    expect(screen.getAllByRole('option')).toHaveLength(1);
  });

  it('offers to add a name that is not in the list', async () => {
    const onCreate = vi.fn();
    render(<Harness onCreate={onCreate} />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    await userEvent.type(input, 'Zuri Naturals');
    await userEvent.click(screen.getByRole('option', { name: 'Add “Zuri Naturals”' }));
    expect(onCreate).toHaveBeenCalledWith('Zuri Naturals');
    expect(input).toHaveValue('Zuri Naturals');
    // An existing name is not offered again.
    await userEvent.clear(input);
    await userEvent.type(input, 'Dresses');
    expect(screen.queryByRole('option', { name: /Add/ })).not.toBeInTheDocument();
  });

  it('clears an optional value when the text is emptied and the field is left', async () => {
    render(<Harness clearable />);
    const input = screen.getByRole('combobox', { name: 'Category' });
    await userEvent.click(input);
    await userEvent.click(screen.getByRole('option', { name: /Dresses/ }));
    await userEvent.clear(input);
    await userEvent.tab();
    expect(screen.getByTestId('value')).toHaveTextContent('');
  });

  it('shows the error message on the field', () => {
    render(<Combobox label="Category" options={OPTIONS} value="" onChange={() => undefined} error="Choose a category" />);
    expect(screen.getByRole('combobox', { name: 'Category' })).toHaveAccessibleDescription('Choose a category');
    expect(screen.getByRole('combobox', { name: 'Category' })).toBeInvalid();
  });
});
