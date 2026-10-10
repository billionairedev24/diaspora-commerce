import { secureStorage } from '@/auth/secureStorage';
import { type CategoryPrefs, DEFAULT_CATEGORY_PREFS } from '@/domain/notifications';

const KEY = 'at.customer.pushCategories';

export async function loadCategoryPrefs(): Promise<CategoryPrefs> {
  try {
    const raw = await secureStorage.get(KEY);
    return raw ? { ...DEFAULT_CATEGORY_PREFS, ...(JSON.parse(raw) as Partial<CategoryPrefs>) } : DEFAULT_CATEGORY_PREFS;
  } catch {
    return DEFAULT_CATEGORY_PREFS;
  }
}

export async function saveCategoryPrefs(p: CategoryPrefs): Promise<void> {
  await secureStorage.set(KEY, JSON.stringify(p));
}
