import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/** Opens a table row's "⋯" menu ("Actions for <row name>") and picks one of its items. */
export async function chooseRowAction(rowName: string | RegExp, item: string | RegExp): Promise<void> {
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: typeof rowName === 'string' ? `Actions for ${rowName}` : rowName }));
  await user.click(await screen.findByRole('menuitem', { name: item }));
}
