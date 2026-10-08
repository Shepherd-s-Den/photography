ALTER TABLE album ADD COLUMN created_at_ms INTEGER NOT NULL DEFAULT 0;

UPDATE album
SET created_at_ms = CAST(strftime('%s', 'now') AS INTEGER) * 1000
WHERE created_at_ms = 0;

INSERT INTO spread_template (id, name, kind)
VALUES ('00000000-0000-4000-8000-000000000001', 'Built-in AlbumStart', 'album_start');

INSERT INTO spread_template_slot (template_id, kind, x, y, width, height)
VALUES ('00000000-0000-4000-8000-000000000001', 'text', 0.0, 0.0, 1.0, 1.0);
