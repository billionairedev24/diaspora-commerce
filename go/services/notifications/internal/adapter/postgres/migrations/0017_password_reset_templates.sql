-- Password reset (S300): the reset code (email, and a text to a verified phone), the "sign in with your provider" email
-- for accounts without a password, and the "your password was changed" notice. Security messages: always sent, no
-- advertorials, never resolved through identity (the payload carries the recipient).

INSERT INTO template (key, locale, channel, version, category, subject, body_html, body_text) VALUES
('identity.password_reset', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.password_reset' AND t.locale = 'en' AND t.channel = 'email'), 'security',
 'Your AfroTransact password reset code: {{.code}}',
 '{{define "preheader"}}Your code is {{.code}}. It expires in {{.expiresInMinutes}} minutes.{{end}}
<h1>Reset your password</h1>
<p>Hi {{.greetingName}}, someone asked to reset the password of your AfroTransact account. Enter this code on the reset page to choose a new password:</p>
{{otp .code}}
<p>The code expires in <strong>{{.expiresInMinutes}} minutes</strong> and works once. Setting a new password signs you out on every device and app.</p>
<p><small>Didn''t ask for this? Ignore this email: your password stays the same. Never share the code; AfroTransact will never ask you for it.</small></p>',
 'Hi {{.greetingName}}, your AfroTransact password reset code is {{.code}}. It expires in {{.expiresInMinutes}} minutes and works once.

Setting a new password signs you out on every device and app. Didn''t ask for this? Ignore this email: your password stays the same. Never share this code.'),
('identity.password_reset', 'en', 'sms', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.password_reset' AND t.locale = 'en' AND t.channel = 'sms'), 'security',
 '',
 '',
 'AfroTransact: your password reset code is {{.code}}. It expires in {{.expiresInMinutes}} min. Never share it. Didn''t ask? Ignore this text.'),
('identity.password_reset.federated', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.password_reset.federated' AND t.locale = 'en' AND t.channel = 'email'), 'security',
 'Sign in to AfroTransact with {{.providerList}}',
 '{{define "preheader"}}Your account has no password to reset: sign in with {{.providerList}}.{{end}}
<h1>Sign in with {{.providerList}}</h1>
<p>Hi {{.greetingName}}, someone asked to reset the password of your AfroTransact account. Your account doesn''t use a password: you sign in with <strong>{{.providerList}}</strong>.</p>
<p>On the sign-in page, choose <strong>Continue with {{.providerList}}</strong>. If you set up a passkey, you can use it too.</p>
{{button .signInUrl "Go to sign in"}}
<p><small>Didn''t ask for this? You can ignore this email; nothing about your account changed. Need help? <a href="{{.helpUrl}}">Visit the Help Center</a>.</small></p>',
 'Hi {{.greetingName}}, someone asked to reset the password of your AfroTransact account. Your account doesn''t use a password: you sign in with {{.providerList}}.

On the sign-in page, choose "Continue with {{.providerList}}": {{.signInUrl}}

Didn''t ask for this? You can ignore this email; nothing about your account changed.'),
('identity.password_changed', 'en', 'email', (SELECT coalesce(max(version), 0) + 1 FROM template t WHERE t.key = 'identity.password_changed' AND t.locale = 'en' AND t.channel = 'email'), 'security',
 'Your AfroTransact password was changed',
 '{{define "preheader"}}Your password was reset and you were signed out everywhere.{{end}}
{{badge "success" "Password changed"}}
<h1>Your password was changed</h1>
<p>Hi {{.greetingName}}, the password of your AfroTransact account was reset with a code on {{.changedOn}}. For your security we signed you out on every device and app; sign in again with your new password.</p>
{{button .signInUrl "Sign in"}}
<p><strong>Wasn''t you?</strong> Reset your password again right away from the sign-in page (Forgot password?) and <a href="{{.helpUrl}}">contact us</a>.</p>',
 'Hi {{.greetingName}}, the password of your AfroTransact account was reset with a code on {{.changedOn}}. For your security we signed you out on every device and app; sign in again with your new password: {{.signInUrl}}

Wasn''t you? Reset your password again right away from the sign-in page (Forgot password?) and contact us: {{.helpUrl}}');
