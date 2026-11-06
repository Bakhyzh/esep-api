-- Operator-only: approved immutable business ID and independently verified issuer/sub.
-- psql -X -v ON_ERROR_STOP=1 -v user_id=... -v issuer=... -v subject=...
--      -v approved_operator=TICKET/operator -v apply=false -f bootstrap-identity.sql
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE approved_identity(user_id bigint,issuer text,subject text,operator text) ON COMMIT DROP;
INSERT INTO approved_identity VALUES (:'user_id',:'issuer',:'subject',:'approved_operator');
DO $$
DECLARE a approved_identity%ROWTYPE;
BEGIN
 SELECT * INTO STRICT a FROM approved_identity;
 IF a.user_id <= 0 OR btrim(a.operator)='' THEN RAISE EXCEPTION 'Missing ID/approval'; END IF;
 INSERT INTO ledger_principals(id,kind) VALUES(a.user_id,'HUMAN') ON CONFLICT DO NOTHING;
 PERFORM id FROM ledger_principals WHERE id=a.user_id AND kind='HUMAN' FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'System principal cannot authenticate'; END IF;
 IF EXISTS (SELECT 1 FROM ledger_identity_bindings WHERE
   (issuer=a.issuer AND subject=a.subject AND user_id<>a.user_id) OR
   (issuer=a.issuer AND user_id=a.user_id AND subject<>a.subject)) THEN
   RAISE EXCEPTION 'Conflicting approved identity';
 END IF;
 INSERT INTO ledger_identity_bindings(user_id,issuer,subject,linked_by)
 SELECT a.user_id,a.issuer,a.subject,a.operator WHERE NOT EXISTS
 (SELECT 1 FROM ledger_identity_bindings WHERE issuer=a.issuer AND subject=a.subject AND user_id=a.user_id);
END $$;
SELECT i.user_id,i.issuer,i.subject,i.linked_at,i.linked_by
FROM ledger_identity_bindings i JOIN approved_identity a ON i.user_id=a.user_id AND i.issuer=a.issuer;
\if :apply
COMMIT;
\else
ROLLBACK;
\endif
