# Editing published pages

**Who:** staff with Site content & legal access. **Where:** Settings → Published pages. No deployment, no developer.

## Change a help page
1. Settings → Published pages → find the page → **Edit** (needs `content:site.edit`, EDIT level).
2. Change the text (`## Heading` before each section). Watch **Checks**: fix repeated section names and links that go nowhere.
3. Look at the live preview (phone width too). **Save draft** (optional: nothing public changes), then **Publish…** and give a reason.
4. Open **View live page** (new tab on the storefront) to confirm. Wrong? **History → Roll back** on the previous revision.

## Change a legal document
1. **Edit** the document (needs `content:legal.edit`). Set the version (higher than the published one), effective date and *What changed*.
   Translations: switch language, **Add a translation**, tick *needs review* until a reviewer has read it.
2. Leave the draft banner on until counsel signs off. Resolve every `[[LEGAL: …]]` marker before publishing as final (banner off).
3. **Save draft.** Ask a colleague with the Approve level and `content:legal.edit` to open the page, read the preview and **Publish…** with a reason.
   The person who saved the last change cannot publish it (403 `content.second-approver-required`).
4. Mistake after publishing: **History → Roll back** on the earlier version starts a draft with its text; a second person publishes it.

## If something is wrong
| Symptom | Cause | Fix |
|---|---|---|
| "Someone changed the draft in the meantime" (409) | Two people edited | Reload; reapply your change |
| "A newer version was published after this draft was started" | Another version went live | Use History → Roll back to restart from the version you want |
| Cannot publish: second approver | You saved last | Ask a colleague |
| Cannot publish: Approve level | Role lacks Approve on Site content & legal | Users & roles |
| Live link 404 | Page is hidden (help page status Draft) or the storefront URL is not set | Check status; `PUBLIC_WEB_URL` of the gateway-bff |
| Page unchanged on the storefront | Content caches public pages for about a minute | Wait, reload |

Audit: content keeps who, when, the reason, author and approver (`audit_log`, `legal.draft-published`, `help-article.draft-published`, `…restored`).
The seed loader (`tools/legal/build_seed.py`) is only for the first load.
