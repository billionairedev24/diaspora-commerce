import { type OnboardingForm } from '@afrotransact/api-client';
import { type AddressSuggestionItem } from '@afrotransact/ui';
import { describe, expect, it } from 'vitest';

import {
  type BusinessValues,
  businessValues,
  type ComplianceValues,
  type DetailsValues,
  displayPhone,
  EMPTY_ADDRESS,
  emptyOwner,
  fieldId,
  formatTaxId,
  fromSuggestion,
  mapServerErrors,
  maskTaxId,
  ownershipTotal,
  type OwnerValues,
  ownerValues,
  payoutAccountLabel,
  prefillNote,
  toComplianceInput,
  toDetailsInput,
  validateAddress,
  validateBusiness,
  validateCompliance,
  validateDetails,
  validateOwners,
  validTaxId
} from '@/lib/onboarding-form';

const TODAY = new Date('2026-10-11T12:00:00Z');
const HOME = {
  line1: '4410 Bellaire Blvd',
  line2: '',
  city: 'Houston',
  state: 'TX',
  postalCode: '77025'
};

const form = (over: Partial<OnboardingForm> = {}): OnboardingForm => ({
  business: {
    offering: null,
    entityType: null,
    country: null,
    storeName: null,
    contactEmail: 'ada@example.com',
    phone: '+17132490142',
    website: null
  },
  details: {
    legalFirstName: 'Adaeze',
    legalLastName: 'Okafor',
    legalName: null,
    dba: null,
    taxIdType: null,
    taxIdMasked: null,
    dateOfBirth: null,
    stateOfFormation: null,
    dateOfFormation: null,
    address: {
      line1: '4410 Bellaire Blvd',
      line2: null,
      city: 'Houston',
      state: 'TX',
      postalCode: '77025',
      country: 'US'
    },
    formationDocumentAssetId: null,
    irsDeterminationLetterAssetId: null
  },
  owners: [],
  payouts: {
    status: 'NOT_STARTED',
    bankName: null,
    accountLast4: null,
    bankStatus: null,
    linked: false
  },
  plan: {
    planCode: null,
    planName: null,
    cadence: null,
    paymentMethodKind: null,
    cardBrand: null,
    cardLast4: null,
    cardExpMonth: null,
    cardExpYear: null,
    firstCharge: null,
    planFeeCollection: null,
    planFeeCollectionAvailable: false,
    planFeeCollectionDefault: 'CARD'
  },
  compliance: {
    categories: [],
    foodPermit: null,
    beautyLabellingConfirmed: null,
    services: [],
    insuranceDocumentAssetId: null,
    agreementsAccepted: null,
    w9Certified: null,
    w9SignerName: null
  },
  documents: [],
  prefilled: ['business.contactEmail', 'business.phone', 'details.address', 'details.legalFirstName', 'details.legalLastName'],
  editable: true,
  ...over
});

describe('Your business', () => {
  it('prefills from the account and shows the phone nationally', () => {
    const v = businessValues(form(), 'SELLER');
    expect(v).toMatchObject({
      offering: 'PRODUCTS',
      entityType: '',
      contactEmail: 'ada@example.com',
      phone: '(713) 249-0142'
    });
    expect(businessValues(form(), 'PROVIDER').offering).toBe('SERVICES');
    expect(displayPhone('+447700900123')).toBe('+447700900123');
  });

  it('needs offering, business type, store name, email and a US mobile; website must be a full URL', () => {
    const v: BusinessValues = {
      offering: '',
      entityType: '',
      storeName: 'A',
      contactEmail: 'nope',
      phone: '713',
      website: 'adeskitchen.com'
    };
    expect(Object.keys(validateBusiness(v, 'SELLER')).sort()).toEqual(['contactEmail', 'entityType', 'offering', 'phone', 'storeName', 'website']);
    expect(
      validateBusiness(
        {
          ...v,
          offering: 'SERVICES',
          entityType: 'SOLE_PROPRIETOR',
          storeName: 'Ade’s Kitchen',
          contactEmail: 'a@b.co',
          phone: '(713) 249-0142',
          website: ''
        },
        'SELLER'
      )
    ).toEqual({
      offering: 'Seller accounts sell products (choose products or both)'
    });
  });
});

describe('About you / Business details & TIN', () => {
  const sole: DetailsValues = {
    legalFirstName: 'Ada',
    legalLastName: 'Okafor',
    legalName: '',
    dba: '',
    taxId: '',
    dateOfBirth: '1990-04-02',
    stateOfFormation: '',
    dateOfFormation: '',
    address: HOME,
    formationDocumentAssetId: '',
    irsDeterminationLetterAssetId: ''
  };

  it('formats and masks the tax ID as it is typed, never showing more than the last 4', () => {
    expect(formatTaxId('123456789', 'SSN')).toBe('123-45-6789');
    expect(formatTaxId('12345', 'SSN')).toBe('123-45');
    expect(formatTaxId('123456789999', 'EIN')).toBe('12-3456789');
    expect(maskTaxId('123-45-6789', 'SSN')).toBe('•••-••-6789');
    expect(maskTaxId('12-3456789', 'EIN')).toBe('••-•••6789');
    expect(validTaxId('123-45-6789', 'SSN')).toBe(true);
    expect(validTaxId('000-45-6789', 'SSN')).toBe(false);
    expect(validTaxId('912-70-1234', 'SSN')).toBe(true);
    expect(validTaxId('07-1234567', 'EIN')).toBe(false);
  });

  it('a sole proprietor needs names, an adult date of birth, the SSN (unless stored) and the address', () => {
    expect(validateDetails(sole, 'SOLE_PROPRIETOR', false, TODAY)).toEqual({
      taxId: 'Enter your SSN or ITIN'
    });
    expect(validateDetails(sole, 'SOLE_PROPRIETOR', true, TODAY)).toEqual({});
    expect(validateDetails({ ...sole, dateOfBirth: '2010-01-01' }, 'SOLE_PROPRIETOR', true, TODAY)).toEqual({ dateOfBirth: 'You must be 18 or older' });
    expect(toDetailsInput({ ...sole, taxId: '123456789' }, 'SOLE_PROPRIETOR')).toMatchObject({
      taxId: '123-45-6789',
      legalFirstName: 'Ada',
      dateOfBirth: '1990-04-02',
      legalName: undefined
    });
    expect(toDetailsInput(sole, 'SOLE_PROPRIETOR').taxId).toBeUndefined();
  });

  it('a company needs the legal name, EIN, formation state and date and the formation document', () => {
    const e = validateDetails({ ...sole, legalName: '', taxId: '12-34' }, 'CORPORATION', false, TODAY);
    expect(Object.keys(e).sort()).toEqual(['dateOfFormation', 'formationDocumentAssetId', 'legalName', 'stateOfFormation', 'taxId']);
    expect(
      validateDetails(
        {
          ...sole,
          legalName: 'X Inc',
          stateOfFormation: 'DE',
          dateOfFormation: '2020-01-01',
          taxId: '12-3456789'
        },
        'NONPROFIT',
        false,
        TODAY
      )
    ).toEqual({
      irsDeterminationLetterAssetId: 'Upload your determination letter'
    });
  });
});

describe('addresses', () => {
  it('picking a suggestion fills street, city, state and ZIP and keeps the apartment', () => {
    const s: AddressSuggestionItem = {
      id: 'x',
      label: '9800 Westheimer Rd',
      secondary: 'Houston, TX 77042',
      source: 'osm',
      address: {
        line1: '9800 Westheimer Rd',
        line2: '',
        city: 'Houston',
        state: 'tx',
        zip: '77042',
        country: 'US',
        lat: null,
        lon: null
      }
    };
    expect(fromSuggestion({ ...EMPTY_ADDRESS, line1: '9800 West', line2: 'Suite 4' }, s)).toEqual({
      line1: '9800 Westheimer Rd',
      line2: 'Suite 4',
      city: 'Houston',
      state: 'TX',
      postalCode: '77042'
    });
  });

  it('validates US states and ZIP codes', () => {
    expect(validateAddress({ ...HOME, state: 'XX', postalCode: '7702' }, 'address')).toEqual({
      'address.state': 'Choose a US state',
      'address.postalCode': 'Enter a 5-digit ZIP code'
    });
    expect(validateAddress({ ...HOME, postalCode: '77025-1234' }, 'address')).toEqual({});
    expect(validateAddress(EMPTY_ADDRESS, 'a', false)).toEqual({});
  });
});

describe('Owners', () => {
  const person = (pct: string, control = false): OwnerValues => ({
    ...emptyOwner(),
    firstName: 'Kofi',
    lastName: 'Mensah',
    title: 'CEO',
    email: 'k@m.co',
    ownershipPercent: pct,
    significantControl: control,
    dateOfBirth: '1980-02-02',
    address: HOME
  });

  it('starts with the applicant as the first person', () => {
    expect(ownerValues(form())[0]).toMatchObject({
      firstName: 'Adaeze',
      lastName: 'Okafor',
      email: 'ada@example.com',
      significantControl: true
    });
  });

  it('sums ownership and refuses more than 100%, no control person, or a lone partner', () => {
    expect(ownershipTotal([person('60'), person('40.5'), person('')])).toBe(100.5);
    expect(validateOwners([person('70', true), person('40')], 'CORPORATION', TODAY).owners).toBe('Ownership adds up to more than 100% (110%)');
    expect(validateOwners([person('60'), person('40')], 'CORPORATION', TODAY).owners).toBe('Mark at least one person with significant control');
    expect(validateOwners([person('100', true)], 'PARTNERSHIP', TODAY).owners).toBe('A partnership needs at least two people');
    expect(validateOwners([person('60', true), person('40')], 'MULTI_MEMBER_LLC', TODAY)).toEqual({});
    expect(validateOwners([person('60', true), person('10')], 'CORPORATION', TODAY)['owners.1.ownershipPercent']).toMatch(/significant control/);
  });
});

describe('Payouts', () => {
  it('shows only what Stripe holds: bank name and last 4 (Stripe collects the account)', () => {
    expect(payoutAccountLabel({ status: 'READY', bankName: 'STRIPE TEST BANK', accountLast4: '6789', bankStatus: 'new', linked: true })).toBe(
      'STRIPE TEST BANK ••6789'
    );
    expect(payoutAccountLabel({ status: 'VERIFYING', bankName: null, accountLast4: '4417', bankStatus: 'new', linked: true })).toBe('Bank account ••4417');
    expect(payoutAccountLabel({ status: 'NEEDS_BANK', bankName: null, accountLast4: null, bankStatus: null, linked: false })).toBe('Not added yet');
  });
});

describe('Catalog, permits & licences', () => {
  const base: ComplianceValues = {
    categories: [],
    permitType: '',
    permitNumber: '',
    permitExpiresOn: '',
    permitDocumentAssetId: '',
    beautyLabellingConfirmed: false,
    services: [],
    insuranceDocumentAssetId: '',
    agreeTerms: true,
    agreePolicy: true,
    w9Certified: true
  };

  it('asks for a food permit and beauty labelling only when those categories are chosen', () => {
    expect(validateCompliance(base, 'PRODUCTS', TODAY)).toEqual({
      categories: 'Pick at least one category'
    });
    expect(Object.keys(validateCompliance({ ...base, categories: ['groceries', 'beauty'] }, 'PRODUCTS', TODAY)).sort()).toEqual([
      'beautyLabellingConfirmed',
      'permitDocumentAssetId',
      'permitExpiresOn',
      'permitNumber',
      'permitType'
    ]);
    expect(validateCompliance({ ...base, categories: ['fashion'] }, 'PRODUCTS', TODAY)).toEqual({});
  });

  it('providers list licences per service and insurance; expired licences are refused', () => {
    const e = validateCompliance(
      {
        ...base,
        services: [
          {
            serviceName: 'Hair & beauty',
            licenceNumber: 'GA-1',
            issuingState: 'GA',
            expiresOn: '2025-01-01',
            documentAssetId: 'a'
          }
        ]
      },
      'SERVICES',
      TODAY
    );
    expect(e).toEqual({
      'services.0.expiresOn': 'Expired licence',
      insuranceDocumentAssetId: 'Upload your insurance certificate'
    });
    const input = toComplianceInput(
      {
        ...base,
        categories: ['groceries'],
        permitType: 'COTTAGE_FOOD',
        permitNumber: 'CF-1',
        permitExpiresOn: '2027-01-01',
        permitDocumentAssetId: 'd'
      },
      'PRODUCTS',
      'Ada Okafor'
    );
    expect(input).toMatchObject({
      categories: ['groceries'],
      foodPermit: { permitType: 'COTTAGE_FOOD' },
      agreementsAccepted: true,
      w9SignerName: 'Ada Okafor',
      services: []
    });
  });
});

describe('server field errors', () => {
  it('map onto the wizard fields', () => {
    expect(
      mapServerErrors({
        'business.storeName': '2 to 80 characters',
        'businessDetails.address.postalCode': 'Enter a 5-digit ZIP code',
        'owners[1].email': 'Enter a valid email address',
        'owners.ownershipTotal': 'Too much',
        'compliance.foodPermit.permitNumber': 'Required',
        'payouts.routingNumber': 'Invalid'
      })
    ).toEqual({
      storeName: '2 to 80 characters',
      'address.postalCode': 'Enter a 5-digit ZIP code',
      'owners.1.email': 'Enter a valid email address',
      owners: 'Too much',
      permitNumber: 'Required',
      routingNumber: 'Invalid'
    });
    expect(fieldId('owners.1.address.city')).toBe('ob-owners-1-address-city');
  });
});

describe('prefill note', () => {
  it('names what came from the account', () => {
    expect(prefillNote(form())).toBe(
      'We filled in your name, email, phone and address from your AfroTransact account (4410 Bellaire Blvd, Houston). Check them — your legal and business details may differ.'
    );
    expect(prefillNote(form({ prefilled: [] }))).toBeNull();
  });
});
