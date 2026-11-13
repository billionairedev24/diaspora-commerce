import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { Field, FormLabelsProvider, InfoTip, MultiCombobox, Select, TextField } from '../src';

const OPTIONS = [
  { value: 'Contains milk', label: 'Milk' },
  { value: 'Contains eggs', label: 'Eggs' },
  { value: 'Contains peanuts', label: 'Peanuts' }
];
const NONE = { value: 'None', label: 'None of these' };

function Allergens({ start = [] as string[] }) {
  const [values, setValues] = useState<string[]>(start);
  return (
    <>
      <MultiCombobox label="Allergens" options={OPTIONS} exclusive={NONE} values={values} onChange={setValues} />
      <span data-testid="value">{values.join('|')}</span>
    </>
  );
}

describe('InfoTip', () => {
  it('opens on click, closes on Escape and gives focus back to the button', async () => {
    render(<InfoTip label="About the main photo">Plain white background</InfoTip>);
    const button = screen.getByRole('button', { name: 'About the main photo' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('note')).toHaveTextContent('Plain white background');
    expect(button).toHaveAttribute('aria-controls', screen.getByRole('note').id);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('note')).toBeNull();
    expect(button).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByRole('note')).toBeInTheDocument();
  });

  it('closes when focus leaves it or the page is clicked elsewhere', async () => {
    render(<><InfoTip label="Info">Text</InfoTip><button type="button">After</button></>);
    await userEvent.click(screen.getByRole('button', { name: 'Info' }));
    await userEvent.tab();
    expect(screen.queryByRole('note')).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Info' }));
    await userEvent.click(document.body);
    expect(screen.queryByRole('note')).toBeNull();
  });
});

describe('required marker', () => {
  it('shows a red asterisk, tells a screen reader "required" and sets aria-required; optional fields carry nothing', () => {
    render(
      <FormLabelsProvider labels={{ required: 'obligatoire' }}>
        <TextField label="Title" required />
        <TextField label="Keywords" />
        <Select label="Condition" required options={[{ value: 'a', label: 'A' }]} />
        <Field label="Free" required>{(a) => <textarea {...a} />}</Field>
      </FormLabelsProvider>
    );
    const title = screen.getByRole('textbox', { name: /Title/ });
    expect(title).toHaveAttribute('aria-required', 'true');
    expect(title).not.toHaveAttribute('required');
    expect(screen.getAllByText('obligatoire')).toHaveLength(3);
    screen.getAllByText('obligatoire').forEach((el) => expect(el).toHaveClass('at-visually-hidden'));
    expect(screen.getByRole('textbox', { name: 'Keywords' })).not.toHaveAttribute('aria-required');
    expect(screen.getByRole('combobox', { name: /Condition/ })).toHaveAttribute('aria-required', 'true');
    expect(screen.getByRole('textbox', { name: /Free/ })).toHaveAttribute('aria-required', 'true');
    expect(document.querySelectorAll('[aria-hidden="true"]')).toHaveLength(3);
  });

  it('puts the info button next to the label, outside the label text', () => {
    render(<TextField label="Weight" tip="At most 150 lb" />);
    expect(screen.getByRole('textbox', { name: 'Weight' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More information' })).toBeInTheDocument();
  });
});

describe('MultiCombobox', () => {
  it('filters by typing, picks with Enter, keeps the list open and shows removable chips', async () => {
    render(<Allergens />);
    const input = screen.getByRole('combobox', { name: 'Allergens' });
    await userEvent.click(input);
    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('listbox')).toHaveAttribute('aria-multiselectable', 'true');
    await userEvent.type(input, 'pea');
    expect(screen.getAllByRole('option')).toHaveLength(1);
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('Contains peanuts');
    expect(screen.getByRole('listbox')).toBeInTheDocument();
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('Contains peanuts|Contains eggs');
    await userEvent.click(screen.getByRole('button', { name: 'Remove Peanuts' }));
    expect(screen.getByTestId('value')).toHaveTextContent(/^Contains eggs$/);
  });

  it('None is exclusive: it clears the others and any other choice clears it', async () => {
    render(<Allergens start={['Contains milk', 'Contains eggs']} />);
    const input = screen.getByRole('combobox', { name: 'Allergens' });
    await userEvent.click(input);
    await userEvent.click(screen.getByRole('option', { name: 'None of these' }));
    expect(screen.getByTestId('value')).toHaveTextContent(/^None$/);
    await userEvent.click(screen.getByRole('option', { name: 'Eggs' }));
    expect(screen.getByTestId('value')).toHaveTextContent(/^Contains eggs$/);
  });

  it('Escape closes the list, Backspace on an empty box removes the last chip', async () => {
    render(<Allergens start={['Contains milk', 'Contains eggs']} />);
    const input = screen.getByRole('combobox', { name: 'Allergens' });
    await userEvent.click(input);
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('listbox')).toBeNull();
    await userEvent.keyboard('{Backspace}');
    expect(screen.getByTestId('value')).toHaveTextContent(/^Contains milk$/);
  });

  it('marks the box required and invalid for assistive technology', () => {
    render(<MultiCombobox label="Allergens" options={OPTIONS} values={[]} onChange={() => undefined} required error="Choose at least one" />);
    const input = screen.getByRole('combobox', { name: /Allergens/ });
    expect(input).toHaveAttribute('aria-required', 'true');
    expect(input).toHaveAttribute('aria-invalid', 'true');
  });
});
