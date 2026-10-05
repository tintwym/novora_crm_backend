CREATE TABLE "automation_rules" (
    "id" TEXT NOT NULL,
    "company_id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "trigger_stage" "DealStage",
    "task_type" "ActivityType" NOT NULL DEFAULT 'TASK',
    "task_subject" TEXT NOT NULL,
    "task_note" TEXT,
    "due_in_days" INTEGER NOT NULL DEFAULT 1,
    "assign_to" TEXT NOT NULL DEFAULT 'DEAL_OWNER',
    "is_active" BOOLEAN NOT NULL DEFAULT true,
    "run_count" INTEGER NOT NULL DEFAULT 0,
    "last_run_at" TIMESTAMP(3),
    "created_by" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "automation_rules_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "automation_rules_due_in_days_check" CHECK ("due_in_days" BETWEEN 0 AND 365),
    CONSTRAINT "automation_rules_assign_to_check" CHECK ("assign_to" IN ('DEAL_OWNER', 'ACTOR'))
);

CREATE INDEX "automation_rules_company_id_idx" ON "automation_rules"("company_id");

ALTER TABLE "automation_rules" ADD CONSTRAINT "automation_rules_company_id_fkey" FOREIGN KEY ("company_id") REFERENCES "companies"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "automation_rules" ADD CONSTRAINT "automation_rules_created_by_fkey" FOREIGN KEY ("created_by") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE "activities" ADD COLUMN "automation_rule_id" TEXT;
CREATE INDEX "activities_automation_rule_id_idx" ON "activities"("automation_rule_id");
ALTER TABLE "activities" ADD CONSTRAINT "activities_automation_rule_id_fkey" FOREIGN KEY ("automation_rule_id") REFERENCES "automation_rules"("id") ON DELETE SET NULL ON UPDATE CASCADE;
