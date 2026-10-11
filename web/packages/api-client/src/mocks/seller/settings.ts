import { settingsHandlers } from '../settings';
import { getSellerDb } from './db';
import { roleLabel } from './model';

/** My account for the signed-in Seller Center member: memberships come from the seller mock's organisations. */
export const sellerSettingsHandlers = settingsHandlers({
  kind: 'seller',
  person: () => getSellerDb().user,
  memberships: () => {
    const db = getSellerDb();
    return db.orgs.map((o) => ({ orgId: o.id, name: o.name, kind: o.kind, status: o.status, roleKey: o.role, roleLabel: roleLabel(o.role), owner: o.role.endsWith('.owner'), active: o.id === db.activeOrgId }));
  },
  leave: (orgId) => {
    const db = getSellerDb();
    db.orgs = db.orgs.filter((o) => o.id !== orgId);
    if (db.activeOrgId === orgId) db.activeOrgId = db.orgs[0]?.id ?? '';
  }
});
