# Production Backup and Restore Runbook

Use a PostgreSQL 17 client for the current Supabase database. Keep full backups encrypted and access-restricted because they contain employee and authentication data.

## Backup

Prefer Supabase managed backups when the project plan provides them. For an additional logical backup, use the direct database connection (not the transaction pooler):

```bash
pg_dump --format=custom --no-owner --no-acl \
  --dbname="$DATABASE_URL" \
  --file="leaflet-ems-$(date +%Y%m%d-%H%M%S).dump"
```

Store the archive in an encrypted private location. Never commit it or place it in application build artifacts.

## Restore drill

Restore only into an isolated disposable PostgreSQL 17 database. The target must have the `btree_gist` extension available before restoring:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
```

Then restore and stop on the first error:

```bash
pg_restore --exit-on-error --no-owner --no-acl \
  --dbname="$RESTORE_DATABASE_URL" \
  leaflet-ems-YYYYMMDD-HHMMSS.dump
```

Verify at minimum:

- Flyway history is present and successful.
- Expected tables, foreign keys, unique constraints, and indexes exist.
- Row counts for critical tables are plausible.
- The application starts against the restored database in a non-production environment.
- Authentication, project, task, attendance, leave, notification, and upload reads work.

Delete the disposable database and securely remove the local archive after the drill. Never restore over production as a test.

## Schedule and ownership

- Run a restore drill before major database releases and at least quarterly.
- Assign one owner for checking backup completion and one independent reviewer for restore results.
- Record the backup timestamp, PostgreSQL version, archive checksum, restore duration, and verification result without recording credentials or employee data.
