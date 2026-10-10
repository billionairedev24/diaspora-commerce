import { expect, type Page, test } from '@playwright/test';

async function addFromProductPage(page: Page, atin: string) {
  await page.goto(`/p/${atin}`);
  await page.getByRole('complementary', { name: 'Buy box' }).getByRole('button', { name: 'Add to Cart' }).click();
  await expect(page.getByRole('complementary', { name: 'Buy box' }).getByText(/in cart$/)).toBeVisible();
}

test('home renders with header, department bar, placements and footer', async ({ page }) => {
  await page.goto('/');
  const header = page.getByRole('banner');
  await expect(header.getByRole('link', { name: 'AfroTransact home' })).toBeVisible();
  await expect(header.getByRole('combobox', { name: 'Search' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Departments' }).getByRole('link', { name: 'Groceries' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Announcements' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Best sellers in Groceries' })).toBeVisible();
  const footer = page.getByRole('contentinfo');
  await expect(footer.getByRole('button', { name: 'Back to top' })).toBeVisible();
  await expect(footer.getByRole('list', { name: 'Follow AfroTransact' }).getByRole('link', { name: /Instagram/ })).toHaveAttribute('href', 'https://www.instagram.com/afrotransact');
  await expect(footer.getByRole('link', { name: '+1 (833) 555-0199' })).toBeVisible();
  // No horizontal page scroll.
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow).toBeLessThanOrEqual(0);
});

test('typeahead shows phrase and product suggestions for "jol"', async ({ page }) => {
  await page.goto('/');
  const box = page.getByRole('combobox', { name: 'Search' });
  await box.fill('jol');
  const list = page.getByRole('listbox', { name: 'Suggestions' });
  await expect(list).toBeVisible();
  await expect(list.getByRole('option').first()).toContainText('jollof');
  await expect(list.getByRole('option', { name: /in Groceries/ })).toBeVisible();
  await expect(list.getByRole('option', { name: /Jollof Seasoning, 6 oz/ })).toBeVisible();
  await box.press('ArrowDown');
  await box.press('Enter');
  await expect(page).toHaveURL(/\/s\?q=jollof&dept=Groceries/);
  await expect(page.getByTestId('results-grid')).toBeVisible();
});

test('search results filter by fulfilment and sync to the URL', async ({ page }) => {
  await page.goto('/s?q=spice');
  await expect(page.getByTestId('results-grid')).toBeVisible();
  const filters = page.getByRole('complementary', { name: 'Filters' });
  await filters.getByRole('radio', { name: /Ships from seller/ }).check();
  await expect(page).toHaveURL(/fulfilment=seller/);
  const cards = page.getByTestId('results-grid').getByRole('article');
  await expect(cards.first()).toBeVisible();
  for (const card of await cards.all()) await expect(card.getByText('Fulfilled by AfroTransact')).toHaveCount(0);
  await filters.getByRole('link', { name: /Somali/ }).click();
  await expect(page).toHaveURL(/community=Somali/);
  await expect(page.getByText(/results for/)).toContainText('"spice"');
  await expect(cards).toHaveCount(4);
  await expect(filters.getByRole('radio', { name: /Ships from seller/ })).toBeChecked();
});

test('PDP add to cart becomes a stepper and stops at stock', async ({ page }) => {
  await page.goto('/p/AT00000P18');
  const box = page.getByRole('complementary', { name: 'Buy box' });
  await expect(box.getByTestId('stock-label')).toHaveText('Only 3 left in stock — order soon.');
  await expect(box.getByText('Fulfilled by AfroTransact')).toBeVisible();
  await box.getByRole('button', { name: 'Add to Cart' }).click();
  await expect(box.getByText('1 in cart')).toBeVisible();
  const plus = box.getByRole('button', { name: /Increase quantity/ });
  await plus.click();
  await expect(box.getByText('2 in cart')).toBeVisible();
  await plus.click();
  await expect(box.getByText('3 in cart')).toBeVisible();
  await expect(plus).toBeDisabled();
  await expect(page.getByTestId('cart-count')).toHaveText('3');
});

test('out-of-stock product offers "Email me when available"', async ({ page }) => {
  await page.goto('/p/AT00000P14');
  await expect(page.getByText('Currently unavailable.')).toBeVisible();
  await page.getByLabel('Your email').fill('ada@example.com');
  await page.getByRole('button', { name: 'Email me when available' }).click();
  await expect(page.getByRole('button', { name: /We’ll email you/ })).toBeVisible();
});

test('cart shows one package per seller, save for later and move back', async ({ page }) => {
  await addFromProductPage(page, 'AT000000P2');
  await addFromProductPage(page, 'AT000000P5');
  await page.goto('/cart');
  const packages = page.getByTestId('cart-package');
  await expect(packages).toHaveCount(2);
  await expect(page.getByText(/Shipped by AfroTransact from/)).toBeVisible();
  await expect(page.getByText(/Shipped by Addis Spice House from Silver Spring, MD/)).toBeVisible();
  await page.getByRole('button', { name: 'Save for later' }).first().click();
  await expect(page.getByTestId('saved-item')).toHaveCount(1);
  await expect(packages).toHaveCount(1);
  await page.getByRole('button', { name: 'Move to cart' }).click();
  await expect(packages).toHaveCount(2);
});

test('/signin has no storefront chrome and shows the Google button and passkey', async ({ page }) => {
  await page.goto('/signin');
  await expect(page.getByRole('banner')).toHaveCount(0);
  await expect(page.getByRole('contentinfo')).toHaveCount(0);
  await expect(page.getByRole('navigation', { name: 'Departments' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'AfroTransact home' })).toBeVisible();
  const google = page.getByRole('link', { name: 'Continue with Google' });
  await expect(google).toBeVisible();
  await expect(google).toHaveAttribute('href', /\/api\/bff\/v1\/auth\/oidc\/google\/authorize/);
  await expect(page.getByRole('link', { name: 'Continue with Apple' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Sign in with a passkey' })).toBeVisible();
  await page.goto('/signup');
  await expect(page.getByRole('button', { name: 'Sign in with a passkey' })).toHaveCount(0);
  await expect(page.getByLabel('Mobile phone')).toBeVisible();
});

test('sign in with email, two-step code, then the avatar menu shows the account', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: /Account menu: Welcome, Guest/ }).click();
  await expect(page.getByRole('menuitem', { name: 'Create account' })).toBeVisible();
  await page.getByRole('menuitem', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/signin/);
  await page.getByLabel('Email').fill('adaeze@example.com');
  await page.getByLabel('Password', { exact: true }).fill('shop2026');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('heading', { name: 'Two-step verification' })).toBeVisible();
  await page.getByLabel('Verification code').fill('246810');
  await page.getByRole('button', { name: 'Verify' }).click();
  await expect(page).toHaveURL(/\/$/);
  await page.getByRole('button', { name: /Account menu: Adaeze Nwosu/ }).click();
  await expect(page.getByRole('menuitem', { name: /Your orders/ })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
});

test('sign-up validates fields per field', async ({ page }) => {
  await page.goto('/signup');
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByRole('alert').filter({ hasText: 'problems with this form' })).toBeVisible();
  await expect(page.getByText('Enter your first name').first()).toBeVisible();
  await page.getByLabel('Mobile phone').fill('7132490142');
  await expect(page.getByLabel('Mobile phone')).toHaveValue('(713) 249-0142');
  await expect(page.getByText('Mobile number looks good')).toBeVisible();
});
