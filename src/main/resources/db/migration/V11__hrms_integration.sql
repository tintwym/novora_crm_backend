-- Novora HRMS integration: link each CRM workspace to one HRMS organization and keep CRM users in
-- step with HRMS employees.

ALTER TABLE "users" ADD COLUMN "hrms_employee_id" TEXT;
ALTER TABLE "users" ADD COLUMN "hrms_synced_at" TIMESTAMP(3);
-- True when the HRMS sync created the account; only those lose access when moving out of sales/support.
ALTER TABLE "users" ADD COLUMN "hrms_provisioned" BOOLEAN NOT NULL DEFAULT false;
-- True only when the HRMS sync (not an admin) deactivated the account, so the sync may re-enable it.
ALTER TABLE "users" ADD COLUMN "hrms_deactivated" BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX "users_company_id_hrms_employee_id_key" ON "users"("company_id", "hrms_employee_id");

CREATE TABLE "hrms_connections" (
    "company_id" TEXT NOT NULL,
    "hrms_org_id" TEXT NOT NULL,
    "hrms_org_name" TEXT,
    "sales_department_codes" TEXT NOT NULL DEFAULT 'SLS,SALES',
    "support_department_codes" TEXT NOT NULL DEFAULT 'SUPPORT,CS',
    "connected_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "connected_by" TEXT,
    "last_sync_at" TIMESTAMP(3),
    "last_sync_status" TEXT,
    "last_sync_message" TEXT,
    "last_event_at" TIMESTAMP(3),
    CONSTRAINT "hrms_connections_pkey" PRIMARY KEY ("company_id")
);

CREATE UNIQUE INDEX "hrms_connections_hrms_org_id_key" ON "hrms_connections"("hrms_org_id");

ALTER TABLE "hrms_connections" ADD CONSTRAINT "hrms_connections_company_id_fkey" FOREIGN KEY ("company_id") REFERENCES "companies"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Event ids already applied, so a redelivered event is acknowledged without being applied twice.
CREATE TABLE "hrms_processed_events" (
    "event_id" TEXT NOT NULL,
    "company_id" TEXT NOT NULL,
    "processed_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "hrms_processed_events_pkey" PRIMARY KEY ("event_id")
);

ALTER TABLE "hrms_processed_events" ADD CONSTRAINT "hrms_processed_events_company_id_fkey" FOREIGN KEY ("company_id") REFERENCES "companies"("id") ON DELETE CASCADE ON UPDATE CASCADE;
