import { setupServer } from 'msw/node';

import { staffHandlers } from './staff/handlers';

/** MSW server for staff console tests: `staffMockServer.listen()` in setup, `resetHandlers()` after each. */
export const staffMockServer = setupServer(...staffHandlers);
