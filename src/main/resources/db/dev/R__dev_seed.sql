-- Dev-only test users. Loaded only with the "dev" profile.
-- Password hashes are placeholders until stage 5 (Security).
INSERT INTO users (email, password_hash, role)
VALUES ('alice@esep.dev', 'not-a-real-hash', 'USER'),
       ('bob@esep.dev',   'not-a-real-hash', 'USER'),
       ('admin@esep.dev', 'not-a-real-hash', 'ADMIN')
ON CONFLICT (email) DO NOTHING;
