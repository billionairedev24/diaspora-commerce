/* Hosted sign-in pages: password show/hide, live password rules, error-summary focus and the passkey ceremony
   (Spring Security's WebAuthn endpoints: POST /webauthn/authenticate/options, POST /login/webauthn). Progressive
   enhancement only: every form works without JavaScript except passkeys, which need the WebAuthn API. */
(() => {
  'use strict';

  const b64url = {
    encode(buffer) {
      return window.btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
    },
    decode(value) {
      const bin = window.atob(value.replace(/-/g, '+').replace(/_/g, '/'));
      const out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
      return out.buffer;
    }
  };

  function csrfHeaders() {
    const header = document.querySelector('meta[name="_csrf_header"]');
    const token = document.querySelector('meta[name="_csrf"]');
    return header && token ? { [header.content]: token.content } : {};
  }

  function announce(el, message) {
    if (!el) return;
    el.querySelector('[data-message]').textContent = message;
    el.hidden = false;
  }

  async function post(url, body) {
    return fetch(url, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...csrfHeaders() },
      body: body ? JSON.stringify(body) : undefined
    });
  }

  async function passkeySignIn(button) {
    const status = document.getElementById(button.dataset.status);
    if (!window.PublicKeyCredential || !navigator.credentials) {
      announce(status, button.dataset.unsupported);
      return;
    }
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    try {
      const optionsResponse = await post('/webauthn/authenticate/options');
      if (!optionsResponse.ok) throw new Error('options ' + optionsResponse.status);
      const options = await optionsResponse.json();
      const publicKey = {
        ...options,
        challenge: b64url.decode(options.challenge),
        allowCredentials: (options.allowCredentials || []).map((c) => ({ ...c, id: b64url.decode(c.id) }))
      };
      let credential;
      try {
        credential = await navigator.credentials.get({ publicKey });
      } catch (err) {
        announce(status, button.dataset.cancelled);
        return;
      }
      const r = credential.response;
      const result = await post('/login/webauthn', {
        id: credential.id,
        rawId: b64url.encode(credential.rawId),
        response: {
          authenticatorData: b64url.encode(r.authenticatorData),
          clientDataJSON: b64url.encode(r.clientDataJSON),
          signature: b64url.encode(r.signature),
          userHandle: r.userHandle ? b64url.encode(r.userHandle) : undefined
        },
        credType: credential.type,
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment
      });
      const body = result.ok ? await result.json() : null;
      if (!body || !body.authenticated || !body.redirectUrl) throw new Error('verify ' + result.status);
      window.location.assign(body.redirectUrl);
    } catch (err) {
      announce(status, button.dataset.failed);
    } finally {
      button.disabled = false;
      button.removeAttribute('aria-busy');
    }
  }

  function setupPasswordToggles() {
    document.querySelectorAll('[data-toggle-password]').forEach((button) => {
      const input = document.getElementById(button.dataset.togglePassword);
      button.hidden = false;
      button.addEventListener('click', () => {
        const show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        button.setAttribute('aria-pressed', String(show));
      });
    });
  }

  function setupPasswordRules() {
    const list = document.querySelector('[data-password-rules]');
    if (!list) return;
    const value = (id) => (document.getElementById(id) || { value: '' }).value;
    const fold = (s) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
    const check = {
      length: (pw) => pw.length >= 12,
      case: (pw) => /\p{Lu}/u.test(pw) && /\p{Ll}/u.test(pw),
      number: (pw) => /\d/.test(pw),
      personal: (pw) => {
        if (!pw) return false;
        const folded = fold(pw);
        const parts = [value('firstName'), value('lastName'), value('email').split('@')[0]]
          .flatMap((v) => fold(v).split(/[\s'’\-._+]+/))
          .filter((p) => p.length >= 3);
        return !parts.some((p) => folded.includes(p));
      },
      match: (pw) => pw.length > 0 && pw === value('confirmPassword')
    };
    const update = () => {
      const pw = value('password');
      list.querySelectorAll('[data-rule]').forEach((li) => {
        const ok = check[li.dataset.rule](pw);
        li.dataset.ok = String(ok);
        li.querySelector('[data-state]').textContent = ok ? li.dataset.met : li.dataset.unmet;
      });
    };
    ['password', 'confirmPassword', 'firstName', 'lastName', 'email'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.addEventListener('input', update);
    });
    update();
  }

  function focusProblems() {
    const summary = document.querySelector('[data-error-summary]');
    if (summary) {
      summary.focus();
      return;
    }
    const invalid = document.querySelector('[aria-invalid="true"]');
    if (invalid) invalid.focus();
  }

  function preventDoubleSubmit() {
    document.querySelectorAll('form[data-once]').forEach((form) => {
      form.addEventListener('submit', () => {
        form.querySelectorAll('button[type="submit"]').forEach((b) => b.setAttribute('aria-busy', 'true'));
      });
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    setupPasswordToggles();
    setupPasswordRules();
    preventDoubleSubmit();
    focusProblems();
    const passkey = document.querySelector('[data-passkey]');
    if (passkey) passkey.addEventListener('click', () => void passkeySignIn(passkey));
  });
})();
