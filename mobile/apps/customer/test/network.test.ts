import { ApiError } from '@afrotransact/api-client';
import { describe, expect, it, vi } from 'vitest';

import { describeError, NetworkError, retryDelay, shouldRetry, withDeadline } from '../src/api/network';

const req = (signal?: AbortSignal) => new Request('http://192.168.1.50:8200/v1/cart', { signal });

describe('withDeadline', () => {
  it('turns a connection failure into a NetworkError naming the host', async () => {
    const f = withDeadline(async () => { throw new TypeError('Network request failed'); });
    const e = await f(req()).catch((x: unknown) => x);
    expect(e).toBeInstanceOf(NetworkError);
    expect((e as NetworkError).host).toBe('192.168.1.50:8200');
    expect((e as NetworkError).timedOut).toBe(false);
  });

  it('gives up on a server that never answers', async () => {
    vi.useFakeTimers();
    const f = withDeadline((r) => new Promise((_, reject) => r.signal.addEventListener('abort', () => reject(new TypeError('aborted')))), 5000);
    const result = f(req()).catch((x: unknown) => x);
    await vi.advanceTimersByTimeAsync(5001);
    const e = await result;
    vi.useRealTimers();
    expect(e).toBeInstanceOf(NetworkError);
    expect((e as NetworkError).timedOut).toBe(true);
  });

  it('lets answers through, including errors the server sent on purpose', async () => {
    const f = withDeadline(async () => new Response('{}', { status: 422 }));
    expect((await f(req())).status).toBe(422);
  });

  it('does not disguise a screen cancelling its own request', async () => {
    const ctrl = new AbortController();
    const abort = new DOMException('aborted', 'AbortError');
    const f = withDeadline(async () => { ctrl.abort(); throw abort; });
    await expect(f(req(ctrl.signal))).rejects.toBe(abort);
  });
});

describe('describeError', () => {
  it('says offline, not "No orders yet", when the server cannot be reached', () => {
    const d = describeError(new NetworkError('10.0.0.5:8200', false));
    expect(d).toMatchObject({ title: 'You seem to be offline', retryable: true });
    expect(d.detail).toContain('10.0.0.5:8200');
  });

  it('separates a server fault (retry) from a refusal (sign in / do not retry)', () => {
    expect(describeError(new ApiError({ type: 'about:blank', title: 'Bad Gateway', status: 502 }))).toMatchObject({ retryable: true });
    expect(describeError(new ApiError({ type: 'about:blank', title: 'Unauthorized', status: 401 }))).toMatchObject({ title: 'Please sign in again', retryable: false });
    expect(describeError(new ApiError({ type: 'about:blank', title: 'Not found', status: 404 }))).toMatchObject({ retryable: false });
  });
});

describe('retry policy', () => {
  it('retries connectivity and server faults twice, never a refusal', () => {
    const net = new NetworkError('h', false);
    expect(shouldRetry(0, net)).toBe(true);
    expect(shouldRetry(1, net)).toBe(true);
    expect(shouldRetry(2, net)).toBe(false);
    expect(shouldRetry(0, new ApiError({ type: 'about:blank', title: 'x', status: 503 }))).toBe(true);
    expect(shouldRetry(0, new ApiError({ type: 'about:blank', title: 'x', status: 404 }))).toBe(false);
    expect(shouldRetry(0, new ApiError({ type: 'about:blank', title: 'x', status: 401 }))).toBe(false);
  });

  it('backs off', () => {
    expect([0, 1, 2, 5].map(retryDelay)).toEqual([1000, 2000, 4000, 8000]);
  });
});
