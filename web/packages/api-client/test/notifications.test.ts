import { describe, expect, it } from 'vitest';

import { countSmsSegments, missingPlaceholders, templatePlaceholders } from '../src';
import { MockRenderError, renderPart } from '../src/mocks/staff/template-render';

describe('notification template rules (same as the Go BFF)', () => {
  it('counts SMS segments in GSM-7 and UCS-2', () => {
    expect(countSmsSegments('')).toEqual({ encoding: 'GSM-7', characters: 0, units: 0, segments: 0, perSegment: 160 });
    expect(countSmsSegments('Total €5')).toMatchObject({ encoding: 'GSM-7', characters: 8, units: 9, segments: 1 });
    expect(countSmsSegments('a'.repeat(161))).toMatchObject({ encoding: 'GSM-7', segments: 2, perSegment: 153 });
    expect(countSmsSegments('Sat, Oct 17 · 10:00 AM')).toMatchObject({ encoding: 'UCS-2', units: 22, segments: 1, perSegment: 70 });
    expect(countSmsSegments(`Hi 👋${'a'.repeat(67)}`)).toMatchObject({ encoding: 'UCS-2', characters: 71, units: 72, segments: 2, perSegment: 67 });
  });

  it('finds the top-level placeholders a template reads', () => {
    expect(templatePlaceholders('Hi {{.firstName}}, {{ .orderNumber }} {{if .note}}{{.note}}{{end}}')).toEqual(['firstName', 'note', 'orderNumber']);
    expect(templatePlaceholders('{{range .lines}}{{.title}} {{$.currency}}{{end}}{{with .x}}{{.y}}{{end}}')).toEqual(['currency', 'lines', 'x']);
    expect(templatePlaceholders('{{printf "%.2f" .amount}} {{/* .no */}}')).toEqual(['amount']);
    expect(missingPlaceholders('{{.a}} {{.b}}', { a: 1 })).toEqual(['b']);
  });
});

describe('mock renderer (the subset of Go templates the seeds use)', () => {
  it('renders fields, if/else and trims, escaping HTML values', () => {
    expect(renderPart('text', 'Hi {{.n}}{{if .x}} with {{.x}}{{else}} alone{{end}}.', { n: 'Ada', x: '' })).toBe('Hi Ada alone.');
    expect(renderPart('text', 'A {{- .n -}} B', { n: 'x' })).toBe('AxB');
    expect(renderPart('html', '<p>{{.n}}</p>', { n: 'Tom & <Jerry>' })).toBe('<p>Tom &amp; &lt;Jerry&gt;</p>');
    expect(renderPart('text', '{{if .missing}}{{.alsoMissing}}{{end}}ok', { missing: false })).toBe('ok');
  });

  it('fails like Go on missing fields and broken templates', () => {
    expect(() => renderPart('subject', 'Order {{.orderNumber}}', {})).toThrow(MockRenderError);
    expect(() => renderPart('subject', 'Order {{.orderNumber}}', {})).toThrow(/map has no entry for key "orderNumber"/);
    expect(() => renderPart('text', '{{if .a}}x', { a: true })).toThrow(/unexpected EOF/);
    expect(() => renderPart('text', '{{.a', { a: 1 })).toThrow(/unclosed action/);
  });
});
