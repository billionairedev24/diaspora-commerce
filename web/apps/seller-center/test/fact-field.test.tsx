import { type AttributeDef } from '@afrotransact/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { FactField } from '@/components/listings/form/FactField';

import { renderWithProviders } from './render';

const def = (extra: Partial<AttributeDef>): AttributeDef => ({
  key: 'q', label: 'Question', options: [], required: true, multiline: false, kind: 'BOOLEAN', units: [], section: 'TAX', help: 'Plain help.', taxFact: true, owner: 'VARIANT', evidence: false, optionLabels: {}, ...extra
});

describe('tax question fields', () => {
  it('asks yes or no and reports the answer', async () => {
    const onChange = vi.fn();
    renderWithProviders(<FactField def={def({})} id="f" value="" onChange={onChange} />);
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /Question/ }), 'yes');
    expect(onChange).toHaveBeenCalledWith('yes');
    expect(screen.getByText('Plain help.')).toBeTruthy();
  });

  it('shows a number with its unit, a percentage sign and the plain labels of a choice', async () => {
    const onChange = vi.fn();
    const { unmount } = renderWithProviders(<FactField def={def({ kind: 'DECIMAL', unit: 'oz', label: 'Net weight (oz)' })} id="w" value="8" onChange={onChange} />);
    expect(screen.getByText('oz')).toBeTruthy();
    expect((screen.getByRole('textbox', { name: /Net weight/ }) as HTMLInputElement).value).toBe('8');
    unmount();
    const pct = renderWithProviders(<FactField def={def({ kind: 'PERCENT', unit: '%' })} id="p" value="" onChange={onChange} />);
    expect(screen.getByText('%')).toBeTruthy();
    pct.unmount();
    renderWithProviders(<FactField def={def({ kind: 'ENUM', options: ['DRUG_FACTS'], optionLabels: { DRUG_FACTS: 'Drug Facts' } })} id="c" value="" onChange={onChange} />);
    await userEvent.selectOptions(screen.getByRole('combobox', { name: /Question/ }), 'DRUG_FACTS');
    expect(onChange).toHaveBeenCalledWith('DRUG_FACTS');
    expect(screen.getByRole('option', { name: 'Drug Facts' })).toBeTruthy();
  });

  it('says a proof file is uploaded on the item page, and marks optional questions', () => {
    renderWithProviders(<FactField def={def({ kind: 'FILE', label: 'Photo of the label', evidence: true })} id="e" value="" onChange={() => undefined} />);
    expect(screen.getByText(/Photo of the label: you can upload this file on the item's page/)).toBeTruthy();
  });
});
