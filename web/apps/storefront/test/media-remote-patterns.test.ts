import { describe, expect, it } from 'vitest';

import { LOCAL_MEDIA_PUBLIC_URL, mediaRemotePatterns } from '../src/lib/media-remote-patterns';

describe('mediaRemotePatterns', () => {
  it('allows the local MinIO bucket by default', () => {
    expect(mediaRemotePatterns([LOCAL_MEDIA_PUBLIC_URL])).toEqual([{ protocol: 'http', hostname: 'localhost', port: '9000', pathname: '/media/**' }]);
  });

  it('follows MEDIA_PUBLIC_URL and skips empty, invalid and duplicate values', () => {
    expect(mediaRemotePatterns(['https://cdn.example.net/', undefined, 'not a url', 'ftp://x.example/a', 'https://cdn.example.net'])).toEqual([
      { protocol: 'https', hostname: 'cdn.example.net', port: '', pathname: '/**' }
    ]);
  });
});
