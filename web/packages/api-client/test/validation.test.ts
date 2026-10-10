import { describe, expect, it } from 'vitest';

import { countryOptions, evaluatePassword, flagOf, formatPhoneAsYouType, isValidMobile, JobApplicationFormSchema, SignUpFormSchema, toE164, toSignUpRequest } from '../src';

describe('phone validation', () => {
  it('formats US numbers as you type', () => {
    expect(formatPhoneAsYouType('713', 'US')).toBe('(713)');
    expect(formatPhoneAsYouType('7132490142', 'US')).toBe('(713) 249-0142');
  });

  it('validates mobile numbers per country', () => {
    expect(isValidMobile('(713) 249-0142', 'US')).toBe(true);
    expect(isValidMobile('123', 'US')).toBe(false);
    expect(isValidMobile('0803 123 4567', 'NG')).toBe(true);
    // Nigerian landline (Lagos) is not a mobile number.
    expect(isValidMobile('01 234 5678', 'NG')).toBe(false);
  });

  it('stores numbers in E.164', () => {
    expect(toE164('(713) 249-0142', 'US')).toBe('+17132490142');
    expect(toE164('024 412 3456', 'GH')).toBe('+233244123456');
    expect(toE164('12', 'US')).toBeNull();
  });

  it('lists every country with flag and dial code, US first', () => {
    const list = countryOptions();
    expect(list.length).toBeGreaterThan(200);
    expect(list[0]).toMatchObject({ code: 'US', dialCode: '+1', flag: flagOf('US') });
    expect(list.find((c) => c.code === 'NG')?.dialCode).toBe('+234');
  });
});

describe('password rules', () => {
  it('reports each rule live', () => {
    const rules = evaluatePassword('short', { confirm: 'x' });
    expect(rules.map((r) => [r.id, r.ok])).toEqual([['length', false], ['case', false], ['number', false], ['personal', true], ['match', false]]);
  });

  it('accepts a strong password that matches', () => {
    expect(evaluatePassword('Kente-Weaver-2026', { confirm: 'Kente-Weaver-2026', email: 'ada@example.com' }).every((r) => r.ok)).toBe(true);
  });

  it('rejects passwords containing the name or email', () => {
    const personal = evaluatePassword('Adaeze-Market-2026', { firstName: 'Adaeze' }).find((r) => r.id === 'personal');
    expect(personal?.ok).toBe(false);
  });
});

describe('sign-up form schema', () => {
  const valid = {
    firstName: 'Adaeze', lastName: 'Nwosu', email: 'ada@example.com', phoneCountry: 'US', phone: '(713) 249-0142',
    password: 'Kente-Weaver-2026', confirmPassword: 'Kente-Weaver-2026', termsAccepted: true, smsConsent: false
  };

  it('accepts the unified sign-up fields', () => {
    const parsed = SignUpFormSchema.parse(valid);
    expect(toSignUpRequest(parsed)).toMatchObject({ phone: '+17132490142', email: 'ada@example.com', termsAccepted: true, smsConsent: false });
  });

  it('reports per-field errors', () => {
    const r = SignUpFormSchema.safeParse({ ...valid, firstName: '', email: 'nope', phone: '12', confirmPassword: 'x', termsAccepted: false });
    expect(r.success).toBe(false);
    const fields = new Set(r.error?.issues.map((i) => i.path[0]));
    expect([...fields].sort()).toEqual(['confirmPassword', 'email', 'firstName', 'phone', 'termsAccepted']);
  });

  it('allows accented and hyphenated names', () => {
    expect(SignUpFormSchema.safeParse({ ...valid, firstName: 'Zoë', lastName: "O'Neil-Adé" }).success).toBe(true);
  });
});

describe('careers application schema', () => {
  it('validates the application form', () => {
    const r = JobApplicationFormSchema.safeParse({ name: 'Al', email: 'x', linkedin: 'https://example.com', workAuthorization: '', resumeFileName: '', consent: false });
    expect(r.success).toBe(false);
    expect(new Set(r.error?.issues.map((i) => i.path[0]))).toEqual(new Set(['name', 'email', 'linkedin', 'workAuthorization', 'resumeFileName', 'resumeAssetId', 'consent']));
    const ok = { name: 'Ama Owusu', email: 'ama@example.com', linkedin: '', workAuthorization: 'Yes', resumeFileName: 'cv.pdf', consent: true };
    expect(JobApplicationFormSchema.safeParse(ok).success).toBe(false); // the résumé must be uploaded first
    expect(JobApplicationFormSchema.safeParse({ ...ok, resumeAssetId: 'asset-1' }).success).toBe(true);
  });
});
