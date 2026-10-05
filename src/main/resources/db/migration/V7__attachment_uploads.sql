ALTER TABLE "attachments" ALTER COLUMN "file_url" DROP NOT NULL;
ALTER TABLE "attachments" ADD COLUMN "storage_key" TEXT;
