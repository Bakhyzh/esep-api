-- Dev-only test users. Loaded only with the "dev" profile.
-- Password for all of them: password123 (BCrypt hash below, {bcrypt} = DelegatingPasswordEncoder prefix).
-- Repeatable migration: Flyway re-runs it whenever this file changes, so DO UPDATE keeps hashes in sync.
INSERT INTO users (email, password_hash, role)
VALUES ('alice@esep.dev', '{bcrypt}$2a$10$/1oWY/VQ0wpMWSFav.NX5up0IxdTIl1bw82hRnTCkg7j9cucCOflC', 'USER'),
       ('bob@esep.dev',   '{bcrypt}$2a$10$/1oWY/VQ0wpMWSFav.NX5up0IxdTIl1bw82hRnTCkg7j9cucCOflC', 'USER'),
       ('admin@esep.dev', '{bcrypt}$2a$10$/1oWY/VQ0wpMWSFav.NX5up0IxdTIl1bw82hRnTCkg7j9cucCOflC', 'ADMIN')
ON CONFLICT (email) DO UPDATE
    SET password_hash = EXCLUDED.password_hash,
        role          = EXCLUDED.role;
