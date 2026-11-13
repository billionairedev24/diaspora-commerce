import { File as NodeFile } from 'node:buffer';

import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ProductForm } from '@/components/listings/ProductForm';

import { renderScreen } from './render';
import { nav } from './setup';

globalThis.File = NodeFile as unknown as typeof File;

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  nav.push.mockReset();
  nav.replace.mockReset();
});

async function chooseCategory(query: string, name: RegExp) {
  fireEvent.change(await screen.findByRole('combobox', { name: 'Category' }), { target: { value: query } });
  fireEvent.click(await screen.findByRole('option', { name }));
}
const form = () => screen.getByRole('form', { name: 'Product' });

describe('required fields (compact product form)', () => {
  it('says "* Required" once at the top, marks every required field and nothing else', async () => {
    await renderScreen(<ProductForm />);
    expect(await screen.findByTestId('required-legend')).toHaveTextContent('* Required');
    const category = await screen.findByRole('combobox', { name: 'Category' });
    expect(category).toHaveAttribute('aria-required', 'true');
    await chooseCategory('spices', /Spices & seasonings/);
    await screen.findByLabelText('Shelf life or expiry');
    const title = within(form()).getByLabelText('Product title');
    expect(title).toHaveAttribute('aria-required', 'true');
    // The asterisk is a picture; the screen reader hears the word, next to the label and not inside the name.
    const mark = title.closest('[data-at-field]')?.querySelector('[aria-hidden="true"]');
    expect(mark?.textContent?.trim()).toBe('*');
    expect(title.closest('[data-at-field]')?.querySelector('.at-visually-hidden')).toHaveTextContent('required');
    expect(screen.getByLabelText('Price (USD)')).toHaveAttribute('aria-required', 'true');
    // Optional fields have no marker at all, and no "(optional)" in their label.
    const sku = screen.getByLabelText('SKU');
    expect(sku).not.toHaveAttribute('aria-required');
    expect(sku.closest('[data-at-field]')?.querySelector('[aria-hidden="true"]')).toBeNull();
    expect(screen.getByLabelText('Compare-at price')).not.toHaveAttribute('aria-required');
    const optionals = within(form()).queryAllByText(/\(optional\)/).map((el) => el.textContent);
    expect(optionals).toEqual(['Label photo (optional)']);
  });

  it('asks for the label photo only where the rules require it: optional for groceries, required with a red asterisk for supplements', async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('spices', /Spices & seasonings/);
    const optional = await screen.findByRole('group', { name: 'Label photo (optional)' });
    expect(optional).not.toHaveTextContent('required');
    screen.queryAllByTestId('missing-list').forEach((list) => expect(list).not.toHaveTextContent(/Label photo/));
    await chooseCategory('butters', /Butters & oils/);
    const required = await screen.findByRole('group', { name: /^Label photo\s*\*?\s*required$/ });
    expect(required.querySelector('[aria-hidden="true"]')?.textContent?.trim()).toBe('*');
  });
});

describe('allergens (compact product form)', () => {
  it('is a dropdown multi-select with removable chips and an exclusive None, stored as the same text', async () => {
    const user = userEvent.setup();
    await renderScreen(<ProductForm />);
    await chooseCategory('spices', /Spices & seasonings/);
    const allergens = await screen.findByRole('combobox', { name: 'Allergens' });
    expect(allergens).not.toHaveAttribute('aria-required');
    await user.click(allergens);
    await user.type(allergens, 'pea');
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Remove Contains peanuts' })).toBeInTheDocument();
    await user.type(allergens, 'mil');
    await user.keyboard('{Enter}');
    expect(screen.getByRole('button', { name: 'Remove Contains milk' })).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: 'None of these' }));
    expect(screen.queryByRole('button', { name: 'Remove Contains peanuts' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove None of these' })).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: 'Contains soy' }));
    expect(screen.queryByRole('button', { name: 'Remove None of these' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Remove Contains soy' }));
    expect(screen.queryByRole('button', { name: /^Remove / })).not.toBeInTheDocument();
  });
});

describe('package row and error timing (compact product form)', () => {
  const openPackage = async () => {
    await renderScreen(<ProductForm />);
    await chooseCategory('spices', /Spices & seasonings/);
    return within(await screen.findByTestId('package-inputs'));
  };

  it('is one row: Length x Width x Height with one size unit, then Weight with its unit; the limits are in an info popover', async () => {
    const pkg = await openPackage();
    ['Length', 'Width', 'Height', 'Weight'].forEach((label) => expect(pkg.getByLabelText(label)).toBeInTheDocument());
    expect(pkg.getAllByRole('combobox')).toHaveLength(2);
    expect(pkg.getByLabelText('Size unit')).toHaveValue('in');
    expect(pkg.getByLabelText('Weight unit')).toHaveValue('lb');
    expect(pkg.queryByText(/At most 108 in/)).not.toBeInTheDocument();
    fireEvent.click(pkg.getByRole('button', { name: 'Package limits' }));
    expect(screen.getByText(/At most 108 in on each side and 150 lb/)).toBeInTheDocument();
    expect(screen.getByTestId('package-inputs')).toHaveAttribute('role', 'group');
  });

  it('shows no error on untouched fields, none while moving between the boxes, one group message after leaving with a problem', async () => {
    const user = userEvent.setup();
    const pkg = await openPackage();
    expect(screen.getByTestId('package-error')).toBeEmptyDOMElement();
    await user.click(pkg.getByLabelText('Length'));
    await user.type(pkg.getByLabelText('Length'), '10');
    await user.tab();
    expect(screen.getByTestId('package-error')).toBeEmptyDOMElement();
    await user.click(pkg.getByLabelText('Width'));
    expect(screen.getByTestId('package-error')).toBeEmptyDOMElement();
    // Leave the whole group (to the next field) with boxes missing: one message under the whole row.
    await user.click(screen.getByLabelText('Product title'));
    await waitFor(() => expect(screen.getByTestId('package-error')).not.toBeEmptyDOMElement());
    expect(screen.getAllByTestId('package-error')).toHaveLength(1);
    expect(pkg.getByLabelText('Length')).toHaveAttribute('aria-invalid', 'true');
    expect(pkg.getByLabelText('Height')).toHaveAttribute('aria-describedby', screen.getByTestId('package-error').id);
  });

  it('does not show an untouched field’s error until the seller tries to submit', async () => {
    const user = userEvent.setup();
    await openPackage();
    expect(screen.queryByText('Enter a title of at least 10 characters.')).not.toBeInTheDocument();
    expect(document.querySelectorAll('[role="alert"]:not(:empty)')).toHaveLength(0);
    await user.click(screen.getByTestId('pf-primary'));
    await waitFor(() => expect(document.querySelectorAll('[role="alert"]:not(:empty)').length).toBeGreaterThan(0));
  });
});
