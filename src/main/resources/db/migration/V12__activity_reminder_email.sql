-- Tracks whether a due-date reminder email was already sent for an activity.
ALTER TABLE "activities" ADD COLUMN "reminder_email_sent_at" TIMESTAMP(3);
