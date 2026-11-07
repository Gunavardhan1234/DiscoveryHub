-- FR-6 verification: record the package's SHA-256 at completion time so
-- POST /exports/{id}/verify can prove the stored zip is byte-identical to
-- the one the worker built (flip a byte in S3 -> verify FAILs).
ALTER TABLE export_jobs ADD COLUMN package_sha256 text;
