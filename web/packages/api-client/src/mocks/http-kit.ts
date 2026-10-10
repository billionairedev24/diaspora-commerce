/* Shared plumbing of the mock BFF handlers: session cookie, problem documents, Zod-validated bodies. */
import { HttpResponse, type JsonBodyType } from 'msw';
import { type z } from 'zod';

import { bffProblemType } from '../problem-types';
import { getSession, type MockSession, userById } from './db';
import { MockProblem } from './service';

export const SESSION_COOKIE = 'at_session';

export interface Ctx { session: MockSession; isNew: boolean; request: Request; now: Date }

export function problem(status: number, code: string, title: string, detail: string, extra: Record<string, unknown> = {}): HttpResponse<JsonBodyType> {
  return HttpResponse.json(
    { type: bffProblemType(code), title, status, detail, code, ...extra } as JsonBodyType,
    { status, headers: { 'Content-Type': 'application/problem+json' } }
  );
}

export function readCookie(request: Request, name: string): string | null {
  const raw = request.headers.get('cookie') ?? '';
  for (const part of raw.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return null;
}

/** Wraps a resolver with session lookup (cookie at_session), problem mapping and Set-Cookie for new sessions. */
export function route(fn: (ctx: Ctx, params: Record<string, string>) => Promise<Response> | Response) {
  return async ({ request, params }: { request: Request; params: Record<string, string | readonly string[] | undefined> }) => {
    const existing = readCookie(request, SESSION_COOKIE);
    const sid = existing ?? crypto.randomUUID();
    const ctx: Ctx = { session: getSession(sid), isNew: !existing, request, now: new Date() };
    const flat: Record<string, string> = {};
    Object.entries(params).forEach(([k, v]) => { if (typeof v === 'string') flat[k] = v; });
    let res: Response;
    try {
      res = await fn(ctx, flat);
    } catch (e) {
      if (e instanceof MockProblem) res = problem(e.status, e.code, e.title, e.message, e.extra);
      else throw e;
    }
    if (ctx.isNew) res.headers.append('Set-Cookie', `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000`);
    return res;
  };
}

export async function body<S extends z.ZodType>(request: Request, schema: S): Promise<z.infer<S>> {
  let json: unknown = {};
  try {
    const text = await request.text();
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new MockProblem(400, 'MALFORMED_JSON', 'Malformed request', 'The request body is not valid JSON.');
  }
  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    const errors: Record<string, string> = {};
    parsed.error.issues.forEach((i) => { errors[i.path.join('.') || '_'] = i.message; });
    throw new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors });
  }
  return parsed.data;
}

export const json = (data: unknown, status = 200) => HttpResponse.json(data as JsonBodyType, { status });

/** The signed-in demo account (401 UNAUTHENTICATED for guests). */
export function requireUser(ctx: Ctx) {
  const u = userById(ctx.session.userId);
  if (!u) throw new MockProblem(401, 'UNAUTHENTICATED', 'Sign in required', 'Sign in to continue.');
  return u;
}
