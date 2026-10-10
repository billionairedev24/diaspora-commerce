/* Built-in place list of the mock pre-order BFF (design/at-preorder.js GEO): the fallback delivery-runs uses when
   geo-service is unavailable. Country → state / province / region → cities. */

export interface GeoRegion {
  name: string;
  cities: string[];
}

export interface GeoCountry {
  name: string;
  regionLabel: string;
  currency: string;
  regions: Record<string, GeoRegion>;
}

const R = (name: string, cities: string): GeoRegion => ({ name, cities: cities.split('|') });

export const GEO: Record<string, GeoCountry> = {
  US: { name: 'United States', regionLabel: 'State', currency: 'USD', regions: {
    AL: R('Alabama', 'Birmingham|Montgomery|Huntsville|Mobile|Tuscaloosa'), AK: R('Alaska', 'Anchorage|Fairbanks|Juneau'), AZ: R('Arizona', 'Phoenix|Tucson|Mesa|Chandler|Scottsdale|Flagstaff'),
    AR: R('Arkansas', 'Little Rock|Fayetteville|Fort Smith|Jonesboro'), CA: R('California', 'Los Angeles|San Diego|San Jose|San Francisco|Oakland|Sacramento|Fresno|Daly City'),
    CO: R('Colorado', 'Denver|Colorado Springs|Aurora|Fort Collins|Boulder'), CT: R('Connecticut', 'Hartford|New Haven|Bridgeport|Stamford'), DE: R('Delaware', 'Wilmington|Dover|Newark'),
    DC: R('District of Columbia', 'Washington'), FL: R('Florida', 'Miami|Orlando|Tampa|Jacksonville|Tallahassee|Fort Lauderdale'), GA: R('Georgia', 'Atlanta|Savannah|Augusta|Columbus|Macon|Athens'),
    HI: R('Hawaii', 'Honolulu|Hilo'), ID: R('Idaho', 'Boise|Idaho Falls|Pocatello'), IL: R('Illinois', 'Chicago|Aurora|Naperville|Rockford|Springfield|Peoria'),
    IN: R('Indiana', 'Indianapolis|Fort Wayne|Evansville|South Bend'), IA: R('Iowa', 'Des Moines|Cedar Rapids|Davenport|Iowa City'), KS: R('Kansas', 'Wichita|Overland Park|Kansas City|Topeka'),
    KY: R('Kentucky', 'Louisville|Lexington|Bowling Green'), LA: R('Louisiana', 'New Orleans|Baton Rouge|Shreveport|Lafayette'), ME: R('Maine', 'Portland|Lewiston|Bangor'),
    MD: R('Maryland', 'Baltimore|Silver Spring|Columbia|Germantown|Bowie'), MA: R('Massachusetts', 'Boston|Worcester|Springfield|Lowell|Cambridge'), MI: R('Michigan', 'Detroit|Grand Rapids|Lansing|Ann Arbor|Flint'),
    MN: R('Minnesota', 'Minneapolis|Saint Paul|Rochester|Duluth|Brooklyn Park'), MS: R('Mississippi', 'Jackson|Gulfport|Hattiesburg'), MO: R('Missouri', 'Kansas City|St. Louis|Springfield|Columbia'),
    MT: R('Montana', 'Billings|Missoula|Bozeman'), NE: R('Nebraska', 'Omaha|Lincoln'), NV: R('Nevada', 'Las Vegas|Henderson|Reno'), NH: R('New Hampshire', 'Manchester|Nashua|Concord'),
    NJ: R('New Jersey', 'Newark|Jersey City|Paterson|Edison|Trenton|Elizabeth'), NM: R('New Mexico', 'Albuquerque|Las Cruces|Santa Fe'), NY: R('New York', 'New York City|Bronx|Brooklyn|Buffalo|Rochester|Albany|Syracuse'),
    NC: R('North Carolina', 'Charlotte|Raleigh|Greensboro|Durham|Fayetteville'), ND: R('North Dakota', 'Fargo|Bismarck|Grand Forks'), OH: R('Ohio', 'Columbus|Cleveland|Cincinnati|Toledo|Akron|Dayton'),
    OK: R('Oklahoma', 'Oklahoma City|Tulsa|Norman'), OR: R('Oregon', 'Portland|Salem|Eugene'), PA: R('Pennsylvania', 'Philadelphia|Pittsburgh|Allentown|Harrisburg'), RI: R('Rhode Island', 'Providence|Warwick'),
    SC: R('South Carolina', 'Columbia|Charleston|Greenville'), SD: R('South Dakota', 'Sioux Falls|Rapid City'), TN: R('Tennessee', 'Nashville|Memphis|Knoxville|Chattanooga'),
    TX: R('Texas', 'Houston|Dallas|Austin|San Antonio|Fort Worth|El Paso|Lubbock|Amarillo|Midland|Odessa|Laredo|Corpus Christi|Waco|Tyler'),
    UT: R('Utah', 'Salt Lake City|Provo|Ogden'), VT: R('Vermont', 'Burlington|Montpelier'), VA: R('Virginia', 'Virginia Beach|Richmond|Arlington|Alexandria|Norfolk'),
    WA: R('Washington', 'Seattle|Spokane|Tacoma|Kent'), WV: R('West Virginia', 'Charleston|Huntington|Morgantown'), WI: R('Wisconsin', 'Milwaukee|Madison|Green Bay'), WY: R('Wyoming', 'Cheyenne|Casper') } },
  CA: { name: 'Canada', regionLabel: 'Province', currency: 'CAD', regions: {
    ON: R('Ontario', 'Toronto|Ottawa|Mississauga|Brampton|Hamilton'), QC: R('Quebec', 'Montreal|Quebec City|Gatineau'), BC: R('British Columbia', 'Vancouver|Surrey|Victoria'),
    AB: R('Alberta', 'Calgary|Edmonton'), MB: R('Manitoba', 'Winnipeg'), SK: R('Saskatchewan', 'Saskatoon|Regina'), NS: R('Nova Scotia', 'Halifax'), NB: R('New Brunswick', 'Moncton|Fredericton'),
    NL: R('Newfoundland and Labrador', "St. John's"), PE: R('Prince Edward Island', 'Charlottetown'), NT: R('Northwest Territories', 'Yellowknife'), YT: R('Yukon', 'Whitehorse'), NU: R('Nunavut', 'Iqaluit') } },
  GB: { name: 'United Kingdom', regionLabel: 'Nation', currency: 'GBP', regions: {
    ENG: R('England', 'London|Manchester|Birmingham|Leeds|Liverpool'), SCT: R('Scotland', 'Glasgow|Edinburgh|Aberdeen'), WLS: R('Wales', 'Cardiff|Swansea'), NIR: R('Northern Ireland', 'Belfast') } },
  NG: { name: 'Nigeria', regionLabel: 'State', currency: 'NGN', regions: {
    LA: R('Lagos', 'Lagos|Ikeja|Lekki'), FC: R('Federal Capital Territory', 'Abuja'), RI: R('Rivers', 'Port Harcourt'), OY: R('Oyo', 'Ibadan'), KN: R('Kano', 'Kano'), EN: R('Enugu', 'Enugu'), AN: R('Anambra', 'Onitsha|Awka') } },
  GH: { name: 'Ghana', regionLabel: 'Region', currency: 'GHS', regions: {
    AA: R('Greater Accra', 'Accra|Tema'), AH: R('Ashanti', 'Kumasi'), WR: R('Western', 'Takoradi'), NR: R('Northern', 'Tamale') } },
  KE: { name: 'Kenya', regionLabel: 'County', currency: 'KES', regions: { NBO: R('Nairobi', 'Nairobi|Westlands|Karen'), MSA: R('Mombasa', 'Mombasa'), KSM: R('Kisumu', 'Kisumu'), NKR: R('Nakuru', 'Nakuru|Naivasha') } },
  ZA: { name: 'South Africa', regionLabel: 'Province', currency: 'ZAR', regions: { GP: R('Gauteng', 'Johannesburg|Pretoria|Soweto'), WC: R('Western Cape', 'Cape Town|Stellenbosch'), KZN: R('KwaZulu-Natal', 'Durban|Pietermaritzburg') } },
  JM: { name: 'Jamaica', regionLabel: 'Parish', currency: 'JMD', regions: { KIN: R('Kingston', 'Kingston'), AND: R('Saint Andrew', 'Half Way Tree|Constant Spring'), JAM: R('Saint James', 'Montego Bay') } },
  IE: { name: 'Ireland', regionLabel: 'County', currency: 'EUR', regions: { D: R('Dublin', 'Dublin|Swords|Tallaght'), CO: R('Cork', 'Cork'), G: R('Galway', 'Galway') } },
};
