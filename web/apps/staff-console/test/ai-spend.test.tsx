import { getStaffDb, resetStaffDb, setMockAiSpend } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { AiSpendView } from '@/components/settings/AiSpendView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

describe('AI and MCP spend cap: today', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
  });

  it('shows what has been spent today against the cap, by feature, from the ledger', async () => {
    const { container } = await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 1.40 of USD 2.00 used (70%)');
    const meter = screen.getByRole('progressbar', { name: "70% of today's limit used" });
    expect(meter).toHaveAttribute('value', '1400000');
    const list = within(screen.getByTestId('ai-by-feature'));
    expect(list.getByText('Lola')).toBeInTheDocument();
    expect(list.getByText('Listing writer')).toBeInTheDocument();
    expect(list.getByText('USD 1.25')).toBeInTheDocument();
    expect(screen.getByText(/15 AI calls and 11 000 tokens|15 AI calls and 11,000 tokens/)).toBeInTheDocument();
    expect(screen.getByText(/The day ends at 00:00 UTC/)).toBeInTheDocument();
    // the cap itself is shown, not edited: no switch, no field, no button to change it
    expect(within(screen.getByTestId('ai-spend')).getByText('USD 2.00 per UTC day (AI features, Lola and the MCP server share it).')).toBeInTheDocument();
    expect(screen.getByText(/AI_GLOBAL_DAILY_COST_USD/)).toBeInTheDocument();
    expect(container.querySelector('input, select, textarea, [role="switch"]')).toBeNull();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('says nothing was recorded, with a spend of zero, when no AI call was made today', async () => {
    setMockAiSpend(getStaffDb(), []);
    await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 0.00 of USD 2.00 used (0%)');
    expect(screen.getByText(/No AI call has been recorded today, so the spend is zero\./)).toBeInTheDocument();
    expect(screen.queryByTestId('ai-by-feature')).not.toBeInTheDocument();
  });

  it('shows a small spend below a cent as such, and is axe-clean', async () => {
    setMockAiSpend(getStaffDb(), [{ feature: 'translation', label: 'Translation', calls: 1, tokens: 120, costMicros: 4_200 }]);
    const { container } = await renderScreen(<AiSpendView />);
    expect(await screen.findByTestId('ai-spent')).toHaveTextContent('USD 0.0042 of USD 2.00');
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations.map((v) => v.id)).toEqual([]);
  });
});
