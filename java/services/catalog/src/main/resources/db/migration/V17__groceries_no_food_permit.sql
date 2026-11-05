-- Groceries (packaged and fresh food retail) no longer asks sellers for a food permit. A permit is a food-service
-- matter (prepared food, catering), handled by the service-provider onboarding, not by the product departments.
update category set compliance = null where level = 'DEPARTMENT' and code = 'groceries' and compliance = 'FOOD_PERMIT';
