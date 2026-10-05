ALTER TABLE contacts ADD COLUMN source TEXT;
ALTER TABLE deals ADD COLUMN source TEXT;

CREATE INDEX deals_company_source_idx ON deals (company_id, source);
