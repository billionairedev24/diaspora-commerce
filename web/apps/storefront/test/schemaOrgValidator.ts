/* A small schema.org validator for the JSON-LD the storefront emits: the REQUIRED properties of each type as schema.org and
   Google's structured-data guidelines define them, enum values, URL and number shapes. Not a replacement for Google's Rich
   Results Test (which needs the public internet and is run by the owner, docs/runbooks/seo-and-search-console.md), but it fails
   the build when a builder drops a required field or invents an invalid value. */

type Json = string | number | boolean | Json[] | { [k: string]: Json };
type Obj = { [k: string]: Json };

const AVAILABILITY = ['InStock', 'OutOfStock', 'LimitedAvailability', 'PreOrder', 'SoldOut', 'BackOrder', 'Discontinued', 'InStoreOnly', 'OnlineOnly'].map((a) => `https://schema.org/${a}`);
const CONDITION = ['NewCondition', 'UsedCondition', 'RefurbishedCondition', 'DamagedCondition'].map((a) => `https://schema.org/${a}`);

const isObj = (v: Json | undefined): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const isAbsUrl = (v: Json | undefined): boolean => typeof v === 'string' && /^https?:\/\/[^\s]+$/.test(v);
const nonEmptyString = (v: Json | undefined): boolean => typeof v === 'string' && v.trim() !== '';
const asArray = (v: Json | undefined): Json[] => (v === undefined ? [] : Array.isArray(v) ? v : [v]);

export function validateJsonLd(node: Json, path = '$'): string[] {
  const errors: string[] = [];
  if (!isObj(node)) return [`${path}: not an object`];
  const type = node['@type'];
  if (path === '$' && node['@context'] !== 'https://schema.org') errors.push(`${path}: @context must be https://schema.org`);
  if (typeof type !== 'string') return [...errors, `${path}: missing @type`];
  const need = (key: string, ok: (v: Json | undefined) => boolean = nonEmptyString) => {
    if (!ok(node[key])) errors.push(`${path}(${type}).${key}: required and missing or invalid`);
  };
  const child = (key: string) => asArray(node[key]).forEach((c, i) => errors.push(...validateJsonLd(c, `${path}.${key}[${i}]`)));

  switch (type) {
    case 'Product': {
      need('name');
      // Google: a Product needs one of offers, review or aggregateRating.
      if (node['offers'] === undefined && node['review'] === undefined && node['aggregateRating'] === undefined) errors.push(`${path}(Product): needs offers, review or aggregateRating`);
      if (node['image'] !== undefined && !asArray(node['image']).every(isAbsUrl)) errors.push(`${path}(Product).image: absolute URLs only`);
      child('brand');
      child('offers');
      child('aggregateRating');
      break;
    }
    case 'Offer': {
      need('price', (v) => typeof v === 'string' && /^\d+(\.\d+)?$/.test(v));
      need('priceCurrency', (v) => typeof v === 'string' && /^[A-Z]{3}$/.test(v));
      if (node['availability'] !== undefined && !AVAILABILITY.includes(node['availability'] as string)) errors.push(`${path}(Offer).availability: not a schema.org ItemAvailability URL`);
      if (node['itemCondition'] !== undefined && !CONDITION.includes(node['itemCondition'] as string)) errors.push(`${path}(Offer).itemCondition: not a schema.org OfferItemCondition URL`);
      if (node['url'] !== undefined && !isAbsUrl(node['url'])) errors.push(`${path}(Offer).url: absolute URL`);
      child('seller');
      break;
    }
    case 'AggregateRating': {
      need('ratingValue', (v) => typeof v === 'number' && v > 0 && v <= 5);
      need('reviewCount', (v) => typeof v === 'number' && Number.isInteger(v) && v > 0);
      break;
    }
    case 'BreadcrumbList': {
      need('itemListElement', (v) => Array.isArray(v) && v.length >= 2);
      asArray(node['itemListElement']).forEach((li, i) => {
        if (!isObj(li) || li['@type'] !== 'ListItem') return void errors.push(`${path}.itemListElement[${i}]: not a ListItem`);
        if (li['position'] !== i + 1) errors.push(`${path}.itemListElement[${i}].position must be ${i + 1}`);
        if (!nonEmptyString(li['name'])) errors.push(`${path}.itemListElement[${i}].name required`);
        // Google: every crumb but the last needs an item URL.
        if (i < asArray(node['itemListElement']).length - 1 && !isAbsUrl(li['item'])) errors.push(`${path}.itemListElement[${i}].item: absolute URL required`);
      });
      break;
    }
    case 'Organization': {
      need('name');
      // A standalone Organization (the home page) states its url; one nested as seller/provider only needs a name.
      if (path === '$') need('url', isAbsUrl);
      if (node['logo'] !== undefined && !isAbsUrl(node['logo'])) errors.push(`${path}(Organization).logo: absolute URL`);
      break;
    }
    case 'WebSite': {
      need('url', isAbsUrl);
      need('name');
      child('potentialAction');
      break;
    }
    case 'SearchAction': {
      const target = node['target'];
      const template = isObj(target) ? target['urlTemplate'] : target;
      if (typeof template !== 'string' || !template.includes('{search_term_string}')) errors.push(`${path}(SearchAction).target must contain {search_term_string}`);
      if (node['query-input'] !== 'required name=search_term_string') errors.push(`${path}(SearchAction).query-input must be "required name=search_term_string"`);
      break;
    }
    case 'LocalBusiness': {
      need('name');
      need('address', isObj);
      child('address');
      break;
    }
    case 'PostalAddress': {
      for (const k of ['streetAddress', 'addressLocality', 'addressRegion', 'postalCode', 'addressCountry']) need(k);
      break;
    }
    case 'Service': {
      need('name');
      need('provider', isObj);
      child('provider');
      child('offers');
      child('aggregateRating');
      break;
    }
    case 'Brand':
    case 'EntryPoint':
    case 'ListItem':
      break;
    default:
      errors.push(`${path}: unknown @type ${type}`);
  }
  return errors;
}

/** Text of the structured data of every kind that must not appear in it (owner rule: no tax claims, no carrier names, no statement that AfroTransact collects payment or delivers). */
export const FORBIDDEN_IN_STRUCTURED_DATA = /\b(tax|vat|duties|customs|ups|fedex|usps|dhl|royal mail|we deliver|we ship|delivered by|shipped by|afrotransact (delivers|collects|ships))\b/i;

export function forbiddenClaims(node: Json): string[] {
  const hits: string[] = [];
  const walk = (v: Json, path: string) => {
    if (typeof v === 'string') {
      const m = FORBIDDEN_IN_STRUCTURED_DATA.exec(v);
      if (m && !/^https?:\/\//.test(v)) hits.push(`${path}: "${m[0]}"`);
    } else if (Array.isArray(v)) v.forEach((x, i) => walk(x, `${path}[${i}]`));
    else if (isObj(v)) for (const [k, x] of Object.entries(v)) walk(x, `${path}.${k}`);
  };
  walk(node, '$');
  return hits;
}
