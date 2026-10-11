/* Mock BFF: help center (GET /v1/help/articles, GET /v1/help/articles/{slug}). Mirrors gateway-bff + content-service:
   the hub is not listed, topics count published articles, the list pages by an opaque cursor, unknown pages are 404. */
import { http } from 'msw';

import { HELP_HUB, HELP_TOPICS, type HelpArticle, isHelpTopicKey } from '../schemas/help';
import { HELP_ARTICLES } from './fixtures/help';
import { json, route } from './http-kit';
import { MockProblem } from './service';

const invalid = (field: string, message: string) =>
  new MockProblem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { errors: { [field]: message } });

const summary = ({ sections: _sections, ...rest }: HelpArticle) => rest;

const matches = (a: HelpArticle, q: string) => {
  const needle = q.trim().toLowerCase();
  return !needle || [a.title, a.summary, ...a.sections.flatMap((s) => [s.heading, s.body])].some((t) => t.toLowerCase().includes(needle));
};

export const helpHandlers = [
  http.get('*/v1/help/articles', route((ctx) => {
    const q = new URL(ctx.request.url).searchParams;
    const topic = (q.get('topic') ?? '').toUpperCase();
    if (topic && !isHelpTopicKey(topic)) throw invalid('topic', 'Unknown help topic');
    const limit = Number(q.get('limit') ?? '20');
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) throw invalid('limit', 'Between 1 and 50');
    const cursor = q.get('cursor');
    let offset = 0;
    try {
      offset = cursor ? Number(atob(cursor).replace(/^o:/, '')) : 0;
    } catch {
      offset = -1;
    }
    if (!Number.isInteger(offset) || offset < 0) throw invalid('cursor', 'Invalid');
    const listed = HELP_ARTICLES.filter((a) => a.slug !== HELP_HUB);
    const matching = listed.filter((a) => (!topic || a.topic === topic) && matches(a, q.get('q') ?? ''));
    const end = Math.min(matching.length, offset + limit);
    const topics = HELP_TOPICS.map((key) => {
      const inTopic = listed.filter((a) => a.topic === key);
      return { key, title: inTopic[0]?.topicTitle ?? key, count: inTopic.length };
    }).filter((t) => t.count > 0);
    return json({ items: matching.slice(offset, end).map(summary), topics, nextCursor: end < matching.length ? btoa(`o:${end}`) : null });
  })),
  http.get('*/v1/help/articles/:slug', route((_ctx, params) => {
    const article = HELP_ARTICLES.find((a) => a.slug === params.slug);
    if (!article) throw new MockProblem(404, 'NOT_FOUND', 'Not found', 'We couldn’t find that help page.');
    return json(article);
  }))
];
