-- The plain public page AfroTransact gives a business that has no website of its own (Stripe is given its address as
-- the business website). It is created with the connected account, shows the business's name, description, logo and one
-- brand colour from its application, and its live listings. The slug is its address and never changes.
create table business_page (
    org_id     varchar(40) primary key references organisation (id),
    slug       varchar(80) not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    created_at timestamptz not null
);
