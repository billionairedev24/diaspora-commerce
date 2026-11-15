-- Staff invitation email (identity.staff-invitation.created; docs/engineering/staff-invitations.md): the invitee opens the
-- link and chooses their own password. The link carries a single-use token (72 hours by default) in its fragment.
-- English only: staff are invited in the working language of the console. Each row becomes the next version of its key.
INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('account.staff_invitation', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'account.staff_invitation' AND t.locale = 'en' AND t.channel = 'email'), 'transactional',
 '{{.inviterName}} invited you to join AfroTransact staff',
 '{{define "preheader"}}Choose your password within {{.expiresInHours}} hours to open the staff console.{{end}}
<h1>Welcome to AfroTransact, {{.firstName}}</h1>
<p>{{.inviterName}} invited you to the AfroTransact staff console as <strong>{{.roleName}}</strong>.</p>
<p>Open the link to choose your own password. It works once and expires in {{.expiresInHours}} hours. The first time you sign in you will also set up two-step verification.</p>
{{button .acceptUrl "Choose your password"}}
<p><small>If you were not expecting this, ignore this email. No account is created until you choose a password.</small></p>',
 '{{.inviterName}} invited you to the AfroTransact staff console as {{.roleName}}.

Choose your own password within {{.expiresInHours}} hours. The link works once, and the first time you sign in you will set up two-step verification.

Choose your password: {{.acceptUrl}}

If you were not expecting this, ignore this email. No account is created until you choose a password.');
