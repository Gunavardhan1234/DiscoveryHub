// Passwords are supplied via environment variables (never hardcoded), so
// this script is safe to keep in version control. mongosh is Node-based
// and exposes the container's environment through process.env.
function requireEnv(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(name + " must be set");
  }
  return value;
}

const archivePassword = requireEnv("ARCHIVE_MONGO_PASSWORD");
const auditPassword = requireEnv("AUDIT_MONGO_PASSWORD");

const archiveDb = db.getSiblingDB("discoveryhub_archive");
archiveDb.createUser({
  user: "archive_svc",
  pwd: archivePassword,
  roles: [{ role: "readWrite", db: "discoveryhub_archive" }]
});

const auditDb = db.getSiblingDB("discoveryhub_audit");
auditDb.createRole({
  role: "auditInsertFind",
  privileges: [{
    resource: { db: "discoveryhub_audit", collection: "audit_events" },
    actions: ["insert", "find"]
  }],
  roles: []
});
auditDb.createUser({
  user: "audit_svc",
  pwd: auditPassword,
  roles: [{ role: "auditInsertFind", db: "discoveryhub_audit" }]
});
