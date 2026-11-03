/**
 * Mock catalog departments and which of them each mock organisation may sell in. Mirrors the server: seller-service
 * records the department codes chosen at onboarding, catalog-service says what each department asks for (compliance
 * data on the node) and enforces the set. Three scenarios to switch between in the org switcher:
 * a food-only seller, a fashion-only seller and a multi-department seller.
 */
export type MockRequirement = 'FOOD_PERMIT' | 'BEAUTY_LABELLING';

export interface MockDepartment {
  /** The tree node id of the department in the mock category tree. */
  id: string;
  /** The catalog department node code (what seller-service stores). */
  code: string;
  name: string;
  requires: MockRequirement[];
}

export const MOCK_DEPARTMENTS: MockDepartment[] = [
  { id: 'dep-groceries', code: 'groceries', name: 'Groceries', requires: ['FOOD_PERMIT'] },
  { id: 'dep-fashion', code: 'fashion', name: 'Fashion', requires: [] },
  { id: 'dep-beauty', code: 'beauty', name: 'Beauty', requires: ['BEAUTY_LABELLING'] },
  { id: 'dep-books', code: 'books', name: 'Books', requires: [] }
];

/** Department codes that ask for `requirement`. */
export function departmentsRequiring(requirement: MockRequirement): Set<string> {
  return new Set(MOCK_DEPARTMENTS.filter((d) => d.requires.includes(requirement)).map((d) => d.code));
}

export const MOCK_FOOD_ORG = 'org-mama-ngozi-pantry';
export const MOCK_FASHION_ORG = 'org-adire-atelier';

/** The departments each mock organisation may sell in (Ade’s Kitchen is the multi-department seller). */
export const MOCK_ORG_DEPARTMENTS: Record<string, string[]> = {
  'org-ade-kitchen': ['groceries', 'fashion', 'beauty', 'books'],
  [MOCK_FOOD_ORG]: ['groceries'],
  [MOCK_FASHION_ORG]: ['fashion'],
  'org-bonwire': ['fashion']
};
