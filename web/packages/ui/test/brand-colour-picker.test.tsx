import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { BrandColourPicker } from '../src';

function Harness({ initial = '', onChange, error }: { initial?: string; onChange?: (v: string) => void; error?: string }) {
  const [v, setV] = useState(initial);
  return (
    <BrandColourPicker
      value={v}
      error={error}
      onChange={(n) => {
        setV(n);
        onChange?.(n);
      }}
    />
  );
}

describe('BrandColourPicker', () => {
  it('is a radiogroup of named swatches with the platform default selected and shown as the default', () => {
    render(<Harness />);
    const group = screen.getByRole('radiogroup', { name: /Brand colour/ });
    expect(group).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Platform default/ })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Green' })).not.toBeChecked();
    expect(screen.getByRole('radio', { name: 'Custom' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Selected: Platform default');
  });

  it('picks a swatch, reports #rrggbb lower case, previews the button with readable text, and can go back to the default', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.click(screen.getByRole('radio', { name: 'Green' }));
    expect(onChange).toHaveBeenLastCalledWith('#1a7f4b');
    const preview = screen.getByTestId('brand-colour-preview');
    expect(preview.style.background).toContain('rgb(26, 127, 75)');
    expect(preview.style.color).toBe('rgb(255, 255, 255)');
    await userEvent.click(screen.getByRole('radio', { name: 'Gold' }));
    expect(screen.getByTestId('brand-colour-preview').style.color).toBe('rgb(17, 17, 17)');
    await userEvent.click(screen.getByRole('radio', { name: /Platform default/ }));
    expect(onChange).toHaveBeenLastCalledWith('');
  });

  it('works with the keyboard alone: arrows move and select within the group', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.tab();
    expect(screen.getByRole('radio', { name: /Platform default/ })).toHaveFocus();
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('radio', { name: 'Green' })).toBeChecked();
    expect(onChange).toHaveBeenLastCalledWith('#1a7f4b');
  });

  it('Custom shows the native picker and a hex field that accept #rgb / #rrggbb with or without # and stay in sync', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    await userEvent.click(screen.getByRole('radio', { name: 'Custom' }));
    const hex = screen.getByLabelText('Hex code');
    await userEvent.type(hex, 'FA0');
    expect(onChange).toHaveBeenLastCalledWith('#ffaa00');
    await userEvent.tab();
    expect(hex).toHaveValue('#ffaa00');
    expect(screen.getByLabelText('Pick a custom colour')).toHaveValue('#ffaa00');
    await userEvent.clear(hex);
    await userEvent.type(hex, '#1A7F4B');
    expect(onChange).toHaveBeenLastCalledWith('#1a7f4b');
  });

  it('flags a hex that is not a colour next to the field, and never reports it as a colour', async () => {
    render(<Harness />);
    await userEvent.click(screen.getByRole('radio', { name: 'Custom' }));
    const hex = screen.getByLabelText('Hex code');
    await userEvent.type(hex, 'green');
    expect(hex).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText(/Enter a colour like/)).toBeInTheDocument();
    expect(screen.getByTestId('brand-colour-preview').style.background).toContain('var(--at-color-action-primary)');
  });

  it('opens Custom for a stored colour that is not a swatch, and selects the swatch that matches a stored one', () => {
    const { unmount } = render(<Harness initial="#123456" />);
    expect(screen.getByRole('radio', { name: 'Custom' })).toBeChecked();
    expect(screen.getByLabelText('Hex code')).toHaveValue('#123456');
    unmount();
    render(<Harness initial="#1a7f4b" />);
    expect(screen.getByRole('radio', { name: 'Green' })).toBeChecked();
  });

  it('shows a server message and takes translated labels', () => {
    render(
      <BrandColourPicker
        value=""
        onChange={() => undefined}
        error="Mauvaise couleur"
        labels={{ label: 'Couleur de marque', defaultName: 'Par défaut', names: { green: 'Vert' } }}
      />
    );
    expect(screen.getByRole('radiogroup', { name: /Couleur de marque/ })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: 'Vert' })).toBeInTheDocument();
    expect(screen.getByText('Mauvaise couleur')).toBeInTheDocument();
  });

  it('cannot be changed when disabled', () => {
    render(
      <fieldset disabled>
        <BrandColourPicker value="" onChange={() => undefined} disabled />
      </fieldset>
    );
    expect(screen.getByRole('radio', { name: 'Green' })).toBeDisabled();
  });
});
