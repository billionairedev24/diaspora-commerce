/* A small robots.txt evaluator with Google's rules (https://developers.google.com/search/docs/crawling-indexing/robots/robots_txt):
   the group for `*`, patterns match from the start of path+query, `*` is any run of characters, `$` anchors the end, and the
   LONGEST matching rule wins (Allow wins a tie). Enough to prove what prod robots.txt does and does not block. */

interface Rule {
  allow: boolean;
  pattern: string;
}

function rulesOf(txt: string): Rule[] {
  const rules: Rule[] = [];
  let inStar = false;
  let seenAgentLine = false;
  for (const raw of txt.split('\n')) {
    const line = raw.replace(/#.*$/, '').trim();
    if (!line) continue;
    const m = /^([A-Za-z-]+)\s*:\s*(.*)$/.exec(line);
    if (!m) continue;
    const key = m[1]!.toLowerCase();
    const value = m[2]!.trim();
    if (key === 'user-agent') {
      if (!seenAgentLine) inStar = false;
      seenAgentLine = true;
      if (value === '*') inStar = true;
    } else {
      seenAgentLine = false;
      if (inStar && (key === 'allow' || key === 'disallow') && value !== '') rules.push({ allow: key === 'allow', pattern: value });
    }
  }
  return rules;
}

function matches(pattern: string, target: string): boolean {
  const anchored = pattern.endsWith('$');
  const body = anchored ? pattern.slice(0, -1) : pattern;
  const re = new RegExp('^' + body.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('.*') + (anchored ? '$' : ''));
  return re.test(target);
}

/** May a crawler fetch `pathAndQuery` ("/s?dept=X")? */
export function robotsAllows(txt: string, pathAndQuery: string): boolean {
  let best: Rule | undefined;
  for (const r of rulesOf(txt)) {
    if (!matches(r.pattern, pathAndQuery)) continue;
    if (!best || r.pattern.length > best.pattern.length || (r.pattern.length === best.pattern.length && r.allow)) best = r;
  }
  return best ? best.allow : true;
}
