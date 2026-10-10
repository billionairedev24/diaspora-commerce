-- S206 metered billing: the organisation a seller / provider member called for, so AI credits can be billed to it.
-- Null for shoppers, staff and service clients (not billable).
alter table ai_usage add column org_id varchar(80);
create index ai_usage_org_day on ai_usage (org_id, usage_day) where org_id is not null;
