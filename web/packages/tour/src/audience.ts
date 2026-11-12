import { type Audience, type Tour } from './schema';

/** What the tour package knows about the signed-in person. The app builds it from its session; nothing here is personal data. */
export interface TourSubject {
  /** Opaque user id (the session's user id, never an email or a name). Progress is kept per id. */
  userId: string;
  roles: readonly string[];
  permissions: readonly string[];
  flags: readonly string[];
}

/** Whether the audience admits the person: roles any-of, permissions all-of, anyPermissions any-of, flags all-of. */
export function audienceAllows(audience: Audience, subject: TourSubject): boolean {
  const roles = new Set(subject.roles);
  const perms = new Set(subject.permissions);
  const flags = new Set(subject.flags);
  if (audience.roles?.length && !audience.roles.some((r) => roles.has(r))) return false;
  if (audience.permissions?.length && !audience.permissions.every((p) => perms.has(p))) return false;
  if (audience.anyPermissions?.length && !audience.anyPermissions.some((p) => perms.has(p))) return false;
  if (audience.flags?.length && !audience.flags.every((f) => flags.has(f))) return false;
  return true;
}

/** The tours a person may see, in the order they were registered. */
export function toursFor(tours: readonly Tour[], subject: TourSubject): Tour[] {
  return tours.filter((t) => audienceAllows(t.audience, subject));
}
