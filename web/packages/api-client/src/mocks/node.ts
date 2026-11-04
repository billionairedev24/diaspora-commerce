import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import { handlers } from './handlers';

/** MSW server for Vitest/Node tests: `server.listen()` in setup, `server.resetHandlers()` after each. */
export const mockServer = setupServer(...handlers);

/** Re-exported so app tests can override one route (mockServer.use(http.post(...))) without depending on msw. */
export { http, HttpResponse };
