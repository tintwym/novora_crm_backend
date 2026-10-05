CREATE TABLE "sample_records" (
    "id" TEXT NOT NULL,
    "company_id" TEXT NOT NULL,
    "entity_type" TEXT NOT NULL,
    "entity_id" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "sample_records_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "sample_records_company_id_entity_type_idx" ON "sample_records"("company_id", "entity_type");

ALTER TABLE "sample_records" ADD CONSTRAINT "sample_records_company_id_fkey" FOREIGN KEY ("company_id") REFERENCES "companies"("id") ON DELETE CASCADE ON UPDATE CASCADE;
