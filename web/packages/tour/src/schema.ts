import { z } from 'zod';

/*
 * Tour definitions are plain data (so they can be validated, tested and, one day, loaded from a server):
 *
 *  - A step points at its target ONLY by a `data-tour="<id>"` attribute (never a CSS selector, never a class or a
 *    generated id), so a refactor of styles cannot break a tour and the drift guard can find every target.
 *  - Titles and bodies are i18n keys of the app's catalogue, not text.
 *  - Who may see a tour is the `audience`: roles, permissions and plan/organisation flags. The apps decide what
 *    those words mean (see TourSubject); the tour package only compares them.
 *  - A step's `when` names a guard the app registers (a function cannot live in data).
 */

/** A target id: lower-case words joined by `-` or `.` (matches the `data-tour` attributes in the apps). */
export const TARGET_ID = /^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$/;
/** An i18n key: dotted lower-camel words, e.g. `sc.tour.products.basics.title`. */
export const I18N_KEY = /^[a-z][A-Za-z0-9]*(?:\.[A-Za-z0-9-]+)+$/;
const ROUTE = /^\/[A-Za-z0-9\-._~/]*(?:\?[A-Za-z0-9\-._~%=&]+)?$/;

export const targetIdSchema = z.string().regex(TARGET_ID, 'a data-tour id: lower-case words joined by "-" or "."');
export const i18nKeySchema = z.string().regex(I18N_KEY, 'an i18n key such as "sc.tour.x.title"');
export const routeSchema = z.string().regex(ROUTE, 'a path starting with "/", optionally with a query such as "?section=tax"');

/** `start` and `end` follow the reading direction (left and right in English, right and left in Arabic). */
export const placementSchema = z.enum(['top', 'bottom', 'start', 'end', 'center']);
export type Placement = z.infer<typeof placementSchema>;

/** The conditions a person must meet to be offered a tour. All given lists must match; an empty audience means everyone signed in. */
export const audienceSchema = z.strictObject({
  /** At least one of these roles (role keys of the app). */
  roles: z.array(z.string().min(1)).optional(),
  /** Every one of these permissions. */
  permissions: z.array(z.string().min(1)).optional(),
  /** At least one of these permissions. */
  anyPermissions: z.array(z.string().min(1)).optional(),
  /** Every one of these flags (for example `offers-services`, `approved`). */
  flags: z.array(z.string().min(1)).optional()
});
export type Audience = z.infer<typeof audienceSchema>;

/**
 * A step can report that the person did the thing it asked for. `appear`/`disappear` watch a target; `click` listens for a click on it.
 * The step then shows a quiet "Done" line; it never blocks Next.
 */
export const successSchema = z.strictObject({
  target: targetIdSchema,
  when: z.enum(['click', 'appear', 'disappear'])
});
export type StepSuccess = z.infer<typeof successSchema>;

export const stepSchema = z.strictObject({
  /** Unique inside the tour; used in tests and logs. */
  id: z.string().regex(TARGET_ID),
  /** The element to point at. Without it the step is centred (a welcome or a wrap-up). */
  target: targetIdSchema.optional(),
  title: i18nKeySchema,
  body: i18nKeySchema,
  placement: placementSchema.default('bottom'),
  /** Go to this page before showing the step (the tour waits for the page). */
  route: routeSchema.optional(),
  /** Name of an app guard; the step is skipped when it says no. */
  when: z.string().regex(TARGET_ID).optional(),
  /** The target depends on data (a banner that only shows when something is wrong, a table row): its absence is normal, not drift. */
  optional: z.boolean().optional(),
  success: successSchema.optional()
});
export type TourStep = z.infer<typeof stepSchema>;

/** An exact path, or a prefix ending in `/*` (for example `/onboarding/*`). */
const autoStartRouteSchema = z.string().regex(/^\/[A-Za-z0-9\-._~/]*(?:\/\*)?$/, 'a path such as "/orders", or a prefix such as "/onboarding/*"');
export const autoStartSchema = z.strictObject({
  /** Start on these pages the first time a person arrives and has not seen this version. */
  routes: z.array(autoStartRouteSchema).min(1)
});
export type AutoStart = z.infer<typeof autoStartSchema>;

export const tourSchema = z
  .strictObject({
    id: z.string().regex(TARGET_ID),
    /** Bump when the tour changes enough that people who finished it should see it again. */
    version: z.number().int().min(1),
    audience: audienceSchema.default({}),
    title: i18nKeySchema,
    /** One line for the Help menu. */
    summary: i18nKeySchema,
    /** The page the tour begins on (the Help menu opens it there). */
    route: routeSchema.optional(),
    autoStart: autoStartSchema.optional(),
    steps: z.array(stepSchema).min(1).max(12)
  })
  .superRefine((tour, ctx) => {
    const seen = new Set<string>();
    tour.steps.forEach((step, i) => {
      if (seen.has(step.id)) ctx.addIssue({ code: 'custom', path: ['steps', i, 'id'], message: `duplicate step id "${step.id}"` });
      seen.add(step.id);
    });
  });

export type Tour = z.infer<typeof tourSchema>;
/** What an author writes (defaults such as `placement` and `audience` may be left out). */
export type TourInput = z.input<typeof tourSchema>;

/** Validates a tour definition; throws a readable error naming the tour. Run at module load so a broken tour fails the tests, not a customer. */
export function defineTour(input: TourInput): Tour {
  const parsed = tourSchema.safeParse(input);
  if (!parsed.success) {
    const id = typeof input === 'object' && input !== null && 'id' in input ? String(input.id) : '?';
    throw new Error(`Invalid tour "${id}": ${parsed.error.issues.map((i) => `${i.path.join('.') || '(tour)'} ${i.message}`).join('; ')}`);
  }
  return parsed.data;
}

/** The i18n keys of a step by convention: `<prefix>.<stepId>.title` and `.body`. Keeps tour files short and the keys greppable. */
export function tourText(prefix: string, stepId: string): { title: string; body: string } {
  return { title: `${prefix}.${stepId}.title`, body: `${prefix}.${stepId}.body` };
}

/** Every `data-tour` id a tour points at (targets and success targets), for the drift guard and the docs. */
export function targetsOf(tour: Tour): string[] {
  const ids = new Set<string>();
  for (const s of tour.steps) {
    if (s.target) ids.add(s.target);
    if (s.success) ids.add(s.success.target);
  }
  return [...ids];
}

/** Every i18n key a tour uses (tour title/summary and each step's title/body), for the catalogue check. */
export function keysOf(tour: Tour): string[] {
  return [tour.title, tour.summary, ...tour.steps.flatMap((s) => [s.title, s.body])];
}
