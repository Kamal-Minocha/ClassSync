-- V007: an ADMIN role for the user-management dashboard.
-- Admins manage accounts only: they have no students/professors row, are never enrolled and teach nothing.
ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('STUDENT', 'PROFESSOR', 'CR', 'ADMIN'));
