-- What a department asks sellers to have on file, as data on the department node (comma-separated requirement codes,
-- read by seller-service when an applicant picks departments). Food sellers need a food permit; beauty sellers confirm
-- ingredient labelling. Other departments carry nothing.
alter table category add column compliance varchar(200);
update category set compliance = 'FOOD_PERMIT' where level = 'DEPARTMENT' and code = 'groceries';
update category set compliance = 'BEAUTY_LABELLING' where level = 'DEPARTMENT' and code = 'beauty';
