# V41 structural-PDF deployment runbook

1. Take and restore-test a V40 or later Supabase logical backup.
2. Rehearse `V41__allow_structurally_validated_pdfs.sql` on the restored database.
3. Confirm legacy and quarantined rows are unchanged and Flyway validation passes.
4. Run the complete PostgreSQL 17 backend suite and the frontend production build.
5. Set `MEDIA_SCANNER_ENABLED=false` on Render. Scanner host, port, and timeout
   variables may be removed; they are optional while scanning is disabled.
6. Keep unsigned Cloudinary presets disabled. Verify signed upload, provider
   response validation, authenticated private download, and provider deletion.
7. Keep `OUTBOX_WORKER_ENABLED=false` until the Google Apps Script webhook passes
   its authenticated delivery contract. Do not weaken the mail production gate.
8. Deploy the backend first, verify Flyway V41 and health, and exercise valid and
   invalid PDF, cross-owner, HR, payment, and private-download cases.
9. Deploy the matching frontend and confirm the structural-validation warning.

Rollback of application code is possible, but V41 is forward-only and should
remain in Flyway history. It adds an allowed controlled value and does not release,
verify, or otherwise rewrite existing media. Accepted PDFs are structurally
validated only and are not claimed to be malware-free.
