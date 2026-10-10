import { type Problem, ProblemSchema } from './schemas/common';

/** Error thrown for non-2xx responses; carries the RFC 9457 problem document. */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
    this.status = problem.status;
    this.problem = problem;
  }

  get code(): string | undefined {
    return this.problem.code;
  }

  /** Field errors keyed by request property (validation problems). */
  get fieldErrors(): Record<string, string> {
    return this.problem.errors ?? {};
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

export async function problemFrom(res: Response): Promise<Problem> {
  try {
    const body: unknown = await res.json();
    const parsed = ProblemSchema.safeParse(body);
    if (parsed.success) return parsed.data;
  } catch {
    /* not JSON */
  }
  return { type: 'about:blank', title: res.statusText || 'Request failed', status: res.status };
}
