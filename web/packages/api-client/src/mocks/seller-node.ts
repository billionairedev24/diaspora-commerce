import { setupServer } from 'msw/node';

import { sellerHandlers } from './seller/handlers';

/** MSW server for Seller Center tests: `sellerMockServer.listen()` in setup, `resetHandlers()` after each. */
export const sellerMockServer = setupServer(...sellerHandlers);
