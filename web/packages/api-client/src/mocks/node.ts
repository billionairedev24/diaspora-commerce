import { setupServer } from 'msw/node';

import { handlers } from './handlers';

/** MSW server for Vitest/Node tests: `server.listen()` in setup, `server.resetHandlers()` after each. */
export const mockServer = setupServer(...handlers);
