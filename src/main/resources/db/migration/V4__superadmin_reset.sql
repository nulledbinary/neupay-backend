-- One-time reset: wipe every existing ADMIN-role user so the application can
-- re-seed a single hard-coded superadmin (ID number 22-14309-736) at startup.
-- The non-cascading FKs to users(id) are first nulled out to keep historical
-- transactions, audit logs and consumed QR tokens intact.

UPDATE transactions
   SET initiated_by_user = NULL
 WHERE initiated_by_user IN (SELECT id FROM users WHERE role = 'ADMIN');

UPDATE qr_tokens
   SET consumed_by = NULL
 WHERE consumed_by IN (SELECT id FROM users WHERE role = 'ADMIN');

UPDATE audit_logs
   SET actor_user_id = NULL
 WHERE actor_user_id IN (SELECT id FROM users WHERE role = 'ADMIN');

DELETE FROM users WHERE role = 'ADMIN';
