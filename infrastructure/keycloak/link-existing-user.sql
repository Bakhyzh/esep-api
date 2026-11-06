-- psql -X -v ON_ERROR_STOP=1 -v local_user_id=... -v issuer=... -v subject=...
--      -v approved_operator=TICKET/operator -v apply=false -f link-existing-user.sql
-- Use trusted issuer/sub and independently verified ownership. Never derive by email.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE approved_link (user_id bigint, issuer text, subject text, operator text) ON COMMIT DROP;
INSERT INTO approved_link VALUES (:'local_user_id'::bigint, :'issuer', :'subject', :'approved_operator');
SELECT u.id, u.role FROM users u JOIN approved_link a ON a.user_id=u.id FOR UPDATE OF u;
DO $$
DECLARE a approved_link%ROWTYPE;
BEGIN
 SELECT * INTO STRICT a FROM approved_link;
 IF NOT EXISTS (SELECT 1 FROM users WHERE id=a.user_id AND email <> 'system@esep.internal') THEN
   RAISE EXCEPTION 'Missing or system user';
 END IF;
 IF btrim(a.operator) = '' OR btrim(a.issuer) = '' OR btrim(a.subject) = '' THEN
   RAISE EXCEPTION 'Approval, issuer and subject required';
 END IF;
 IF EXISTS (SELECT 1 FROM user_identities WHERE
     (issuer=a.issuer AND subject=a.subject AND user_id<>a.user_id)
     OR (issuer=a.issuer AND user_id=a.user_id AND subject<>a.subject)) THEN
   RAISE EXCEPTION 'Conflicting identity mapping; do not reassign';
 END IF;
 INSERT INTO user_identities(user_id,issuer,subject,linked_by)
 SELECT a.user_id,a.issuer,a.subject,a.operator
 WHERE NOT EXISTS (SELECT 1 FROM user_identities WHERE user_id=a.user_id AND issuer=a.issuer AND subject=a.subject);
END $$;
SELECT i.id,i.user_id,i.issuer,i.subject,i.linked_at,i.linked_by
FROM user_identities i JOIN approved_link a ON i.user_id=a.user_id AND i.issuer=a.issuer;
\if :apply
COMMIT;
\else
ROLLBACK;
\endif
