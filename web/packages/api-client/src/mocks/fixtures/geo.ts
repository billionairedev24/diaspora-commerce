/* Locations, fulfilment centres and addresses from the prototype (GEO, FCS, ZIPS, ADDRS, STREETS, STATES). */

export const GEO: Record<string, [number, number]> = {
  'Houston, TX': [29.76, -95.37], 'Bronx, NY': [40.84, -73.87], 'Brooklyn, NY': [40.68, -73.94], 'Silver Spring, MD': [38.99, -77.03], 'Atlanta, GA': [33.75, -84.39],
  'Columbus, OH': [39.96, -83.0], 'Minneapolis, MN': [44.98, -93.27], 'Edison, NJ': [40.52, -74.41], 'Miami, FL': [25.76, -80.19], 'Daly City, CA': [37.69, -122.47],
  'Los Angeles, CA': [34.05, -118.24], 'Charlotte, NC': [35.23, -80.84], 'Dallas, TX': [32.78, -96.8], 'Newark, NJ': [40.74, -74.17], 'Chicago, IL': [41.88, -87.63], 'Seattle, WA': [47.61, -122.33]
};

export const FULFILMENT_CENTRES: ReadonlyArray<[name: string, city: string]> = [
  ['Houston FC', 'Houston, TX'], ['Edison FC', 'Edison, NJ'], ['Atlanta FC', 'Atlanta, GA'], ['Los Angeles FC', 'Los Angeles, CA']
];

/** First two ZIP digits → serviceable metro. */
export const ZIP_PREFIXES: Record<string, string> = {
  '77': 'Houston, TX', '10': 'Bronx, NY', '11': 'Brooklyn, NY', '20': 'Silver Spring, MD', '30': 'Atlanta, GA', '08': 'Edison, NJ', '07': 'Newark, NJ', '55': 'Minneapolis, MN',
  '33': 'Miami, FL', '94': 'Daly City, CA', '90': 'Los Angeles, CA', '60': 'Chicago, IL', '75': 'Dallas, TX', '28': 'Charlotte, NC', '98': 'Seattle, WA', '43': 'Columbus, OH'
};

export interface FixtureAddress { line1: string; city: string; state: string; zip: string; geo: string; multi?: boolean }

export const ADDRESSES: readonly FixtureAddress[] = [
  { line1: '9800 Westheimer Rd', city: 'Houston', state: 'TX', zip: '77042', geo: 'Houston, TX' },
  { line1: '2400 Fountain View Dr', city: 'Houston', state: 'TX', zip: '77057', geo: 'Houston, TX', multi: true },
  { line1: '7100 Bellaire Blvd', city: 'Houston', state: 'TX', zip: '77074', geo: 'Houston, TX' },
  { line1: '1250 Grand Concourse', city: 'Bronx', state: 'NY', zip: '10456', geo: 'Bronx, NY', multi: true },
  { line1: '8605 Colesville Rd', city: 'Silver Spring', state: 'MD', zip: '20910', geo: 'Silver Spring, MD', multi: true },
  { line1: '455 Edgewood Ave SE', city: 'Atlanta', state: 'GA', zip: '30312', geo: 'Atlanta, GA' },
  { line1: '1430 Oak Tree Rd', city: 'Iselin', state: 'NJ', zip: '08830', geo: 'Edison, NJ' },
  { line1: '2901 Cedar Ave S', city: 'Minneapolis', state: 'MN', zip: '55407', geo: 'Minneapolis, MN' },
  { line1: '7700 NW 2nd Ave', city: 'Miami', state: 'FL', zip: '33150', geo: 'Miami, FL' },
  { line1: '333 Gellert Blvd', city: 'Daly City', state: 'CA', zip: '94015', geo: 'Daly City, CA' },
  { line1: '1200 W Harrison St', city: 'Chicago', state: 'IL', zip: '60607', geo: 'Chicago, IL', multi: true }
];

export const STREETS: ReadonlyArray<[street: string, city: string, state: string, zip: string]> = [
  ['Westheimer Rd', 'Houston', 'TX', '77042'], ['Bellaire Blvd', 'Houston', 'TX', '77036'], ['Main St', 'Houston', 'TX', '77002'], ['Main St', 'Silver Spring', 'MD', '20910'],
  ['Market St', 'Newark', 'NJ', '07102'], ['Martin Luther King Jr Blvd', 'Atlanta', 'GA', '30310'], ['Malcolm X Blvd', 'New York', 'NY', '10026'], ['Grand Concourse', 'Bronx', 'NY', '10456'],
  ['Georgia Ave NW', 'Washington', 'DC', '20011'], ['Colesville Rd', 'Silver Spring', 'MD', '20910'], ['Edgewood Ave SE', 'Atlanta', 'GA', '30312'], ['Peachtree St NE', 'Atlanta', 'GA', '30303'],
  ['Fountain View Dr', 'Houston', 'TX', '77057'], ['Central Ave', 'Minneapolis', 'MN', '55418'], ['Lake St', 'Minneapolis', 'MN', '55407'], ['Halsted St', 'Chicago', 'IL', '60608'],
  ['Pine St', 'Seattle', 'WA', '98101'], ['Oak Lawn Ave', 'Dallas', 'TX', '75219'], ['Elm St', 'Dallas', 'TX', '75201'], ['Washington Blvd', 'Los Angeles', 'CA', '90018']
];

export { US_STATES } from '../../us-states';

export interface FixturePickupPoint { id: string; owner: string; metro: string; name: string; addr: string; hours: string; ready: string }

export const PICKUP_POINTS: readonly FixturePickupPoint[] = [
  ['pu-hou-fc', 'AT', 'Houston, TX', 'Houston FC — Will Call', '12000 Bissonnet St, Houston, TX 77099', 'Mon–Sat 8 am–8 pm', 'Ready in 2 hours'],
  ['pu-hou-lock', 'AT', 'Houston, TX', 'AfroTransact Locker · Bellaire', '7100 Bellaire Blvd, Houston, TX 77074', 'Open 24/7', 'Ready tomorrow 9 am'],
  ['pu-edi-fc', 'AT', 'Edison, NJ', 'Edison FC — Will Call', '300 Raritan Center Pkwy, Edison, NJ 08837', 'Mon–Sat 8 am–8 pm', 'Ready in 2 hours'],
  ['pu-atl-fc', 'AT', 'Atlanta, GA', 'Atlanta FC — Will Call', '1600 Murphy Ave SW, Atlanta, GA 30310', 'Mon–Sat 8 am–6 pm', 'Ready in 3 hours'],
  ['pu-mamatee', "Mama Tee's Pantry", 'Houston, TX', "Mama Tee's Pantry storefront", '7100 Bellaire Blvd, Unit 14, Houston, TX 77074', 'Tue–Sun 10 am–7 pm', 'Ready in 3 hours'],
  ['pu-lagos', 'Lagos Textile Hub', 'Houston, TX', 'Lagos Textile Hub showroom', '6655 Hillcroft Ave, Houston, TX 77081', 'Mon–Sat 11 am–6 pm', 'Ready tomorrow'],
  ['pu-patel', 'Patel Grocers', 'Edison, NJ', 'Patel Grocers counter', '1430 Oak Tree Rd, Iselin, NJ 08830', 'Daily 9 am–9 pm', 'Ready in 2 hours'],
  ['pu-dakar', 'Dakar Market', 'Bronx, NY', 'Dakar Market', '1250 Grand Concourse, Bronx, NY 10456', 'Daily 10 am–8 pm', 'Ready tomorrow'],
  ['pu-kingston', 'Kingston Kitchen', 'Miami, FL', 'Kingston Kitchen pickup window', '7700 NW 2nd Ave, Miami, FL 33150', 'Wed–Sun 11 am–7 pm', 'Ready in 4 hours']
].map(([id, owner, metro, name, addr, hours, ready]) => ({ id, owner, metro, name, addr, hours, ready }) as FixturePickupPoint);
