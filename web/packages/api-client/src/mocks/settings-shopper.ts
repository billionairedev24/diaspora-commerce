import { getDb, userById } from './db';
import { readCookie, SESSION_COOKIE } from './http-kit';
import { settingsHandlers } from './settings';

/** My account for the signed-in storefront customer (the demo account of the mock session cookie). */
function account(request: Request) {
  const sid = readCookie(request, SESSION_COOKIE);
  return userById(sid ? (getDb().sessions.get(sid)?.userId ?? null) : null);
}

export const shopperSettingsHandlers = settingsHandlers({
  kind: 'customer',
  person: (request) => {
    const u = account(request);
    return u ? { id: u.id, firstName: u.firstName, lastName: u.lastName, email: u.email } : null;
  },
  password: {
    check: (request, current) => account(request)?.password === current,
    set: (request, next) => {
      const u = account(request);
      if (u) u.password = next;
    }
  }
});
