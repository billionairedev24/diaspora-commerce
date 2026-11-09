import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Checklist, ProgressMeter } from '../src';

describe('ProgressMeter', () => {
  it('is a progress bar named by its label, with the figure and the explanation as words', () => {
    render(<ProgressMeter label="Stripe Tax" value={78} max={104} valueText="78 of 104 outcomes verified" help="26 outcomes still need a code." />);
    const bar = screen.getByRole('progressbar', { name: 'Stripe Tax' });
    expect(bar).toHaveAttribute('aria-valuenow', '78');
    expect(bar).toHaveAttribute('aria-valuemax', '104');
    expect(bar).toHaveAttribute('aria-valuetext', '78 of 104 outcomes verified');
    expect(screen.getByText('78 of 104 outcomes verified')).toBeInTheDocument();
    expect(screen.getByText('26 outcomes still need a code.')).toBeInTheDocument();
    expect((bar.firstElementChild as HTMLElement).style.inlineSize).toBe('75%');
  });

  it('treats nothing to count as complete and never overflows', () => {
    const { rerender } = render(<ProgressMeter label="A" value={0} max={0} valueText="nothing to verify" />);
    expect((screen.getByRole('progressbar').firstElementChild as HTMLElement).style.inlineSize).toBe('100%');
    rerender(<ProgressMeter label="A" value={9} max={5} valueText="9 of 5" />);
    expect((screen.getByRole('progressbar').firstElementChild as HTMLElement).style.inlineSize).toBe('100%');
  });
});

describe('Checklist', () => {
  it('writes each result as text as well as a mark', () => {
    render(
      <Checklist
        label="Ready?"
        items={[
          { key: 'a', title: 'Long enough', ok: true, detail: '31 days.' },
          { key: 'b', title: 'Same answer', ok: false, detail: '2 lines differ.' }
        ]}
      />
    );
    const list = screen.getByRole('list', { name: 'Ready?' });
    expect(list).toHaveTextContent('Long enough: passes — 31 days.');
    expect(list).toHaveTextContent('Same answer: needs work — 2 lines differ.');
    list.querySelectorAll('svg').forEach((svg) => expect(svg).toHaveAttribute('aria-hidden', 'true'));
  });

  it('takes translated pass and fail words', () => {
    render(<Checklist label="Contrôles" passText="réussi" failText="à revoir" items={[{ key: 'a', title: 'Durée', ok: false, detail: '3 jours.' }]} />);
    expect(screen.getByRole('list')).toHaveTextContent('Durée: à revoir — 3 jours.');
  });
});
