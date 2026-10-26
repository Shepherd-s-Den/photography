ALTER TABLE chapter ADD COLUMN is_home INTEGER NOT NULL DEFAULT 0 CHECK (is_home IN (0, 1));

UPDATE chapter SET is_home = (SELECT name = 'Home' FROM destination WHERE id = chapter.destination_id);

CREATE UNIQUE INDEX chapter_one_home_per_album ON chapter (album_id) WHERE is_home = 1;

CREATE TRIGGER chapter_home_insert BEFORE INSERT ON chapter
WHEN NEW.is_home != (SELECT name = 'Home' FROM destination WHERE id = NEW.destination_id)
BEGIN
    SELECT RAISE(ABORT, 'Chapter Home representation mismatch');
END;

CREATE TRIGGER chapter_home_update BEFORE UPDATE OF destination_id, is_home ON chapter
WHEN NEW.is_home != (SELECT name = 'Home' FROM destination WHERE id = NEW.destination_id)
BEGIN
    SELECT RAISE(ABORT, 'Chapter Home representation mismatch');
END;

CREATE TRIGGER destination_home_update AFTER UPDATE OF name ON destination
BEGIN
    UPDATE chapter SET is_home = (NEW.name = 'Home') WHERE destination_id = NEW.id;
END;

CREATE VIEW invalid_album_composition AS
SELECT a.id AS album_id FROM album a
WHERE (SELECT count(*) FROM spread WHERE album_id = a.id AND kind = 'album_start') != 1
   OR (SELECT count(*) FROM cover WHERE album_id = a.id) != 1
   OR (SELECT count(*) FROM cover_slot WHERE album_id = a.id) != 2
   OR EXISTS (
       SELECT 1 FROM chapter c WHERE c.album_id = a.id AND (
           c.position < 0
           OR NOT EXISTS (SELECT 1 FROM destination_country WHERE destination_id = c.destination_id)
           OR (SELECT count(*) FROM spread WHERE chapter_id = c.id AND kind = 'chapter_start') != 1
       )
   )
   OR EXISTS (
       SELECT 1 FROM spread s LEFT JOIN chapter c ON c.id = s.chapter_id
       LEFT JOIN spread_template t ON t.id = s.template_id
       WHERE (s.album_id = a.id OR c.album_id = a.id) AND (
           t.kind IS NULL OR s.kind != t.kind
           OR length(t.name) = 0
           OR (s.kind IN ('album_start', 'chapter_start') AND s.position != 0)
           OR (s.kind = 'photos_only' AND s.position <= 0)
           OR (SELECT count(*) FROM spread_template_slot WHERE template_id = t.id) = 0
           OR (s.kind IN ('album_start', 'chapter_start') AND
               (SELECT count(*) FROM spread_template_slot WHERE template_id = t.id AND kind = 'text') != 1)
           OR (s.kind = 'photos_only' AND
               (SELECT count(*) FROM spread_template_slot WHERE template_id = t.id AND kind = 'text') != 0)
           OR NOT EXISTS (SELECT 1 FROM spread_template_slot WHERE template_id = t.id AND kind = 'photo')
           OR EXISTS (
               SELECT 1 FROM spread_template_slot ts WHERE ts.template_id = t.id AND (
                   ts.kind NOT IN ('photo', 'text')
                   OR ts.x < 0 OR ts.y < 0 OR ts.width <= 0 OR ts.height <= 0
                   OR ts.x >= 1.7976931348623157e308 OR ts.y >= 1.7976931348623157e308
                   OR ts.width >= 1.7976931348623157e308 OR ts.height >= 1.7976931348623157e308
               )
           )
           OR (SELECT count(*) FROM spread_template_slot WHERE template_id = t.id)
              != (SELECT count(*) FROM spread_photo_slot WHERE spread_id = s.id)
               + (SELECT count(*) FROM spread_text_slot WHERE spread_id = s.id)
           OR EXISTS (
               SELECT 1 FROM spread_template_slot ts WHERE ts.template_id = t.id AND NOT EXISTS (
                   SELECT 1 FROM spread_photo_slot ps WHERE ts.kind = 'photo' AND ps.spread_id = s.id
                       AND (ps.x, ps.y, ps.width, ps.height) = (ts.x, ts.y, ts.width, ts.height)
                   UNION ALL
                   SELECT 1 FROM spread_text_slot xs WHERE ts.kind = 'text' AND xs.spread_id = s.id
                       AND (xs.x, xs.y, xs.width, xs.height) = (ts.x, ts.y, ts.width, ts.height)
               )
           )
           OR EXISTS (
               SELECT 1 FROM spread_photo_slot ps JOIN spread_text_slot xs ON ps.id = xs.id
               WHERE ps.spread_id = s.id OR xs.spread_id = s.id
           )
       )
   );
