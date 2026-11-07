#!/bin/sh
# LocalStack "ready" hook (runs after the S3 service is up).
# Auto-creates the two buckets the platform needs so a fresh
# `docker compose up -d` is immediately usable, no manual `awslocal` step.
set -e

echo "[localstack-init] creating S3 buckets..."

# Always target us-east-1 inside LocalStack: it's the only region that accepts
# a CreateBucket without an explicit LocationConstraint, so we avoid the
# IllegalLocationConstraintException that non-us-east-1 regions throw when
# AWS_REGION (e.g. eu-north-1) is inherited by awslocal.
REGION="--region us-east-1"

# We check existence first and treat a 400 NoSuchBucket-then-create as idempotent.
for bucket in discoveryhub-attachments discoveryhub-exports; do
  if awslocal s3api head-bucket --bucket "$bucket" $REGION >/dev/null 2>&1; then
    echo "[localstack-init] $bucket already exists"
  else
    awslocal s3api create-bucket --bucket "$bucket" $REGION >/dev/null
    echo "[localstack-init] $bucket created"
  fi
done

# Versioning on the exports bucket (reproducible packages + history).
awslocal s3api put-bucket-versioning \
  --bucket discoveryhub-exports $REGION \
  --versioning-configuration Status=Enabled >/dev/null 2>&1 || true

echo "[localstack-init] buckets ready: discoveryhub-attachments, discoveryhub-exports"
