ALTER TABLE album ADD COLUMN authors INTEGER NOT NULL DEFAULT 7
    CHECK (typeof(authors) = 'integer' AND authors BETWEEN 1 AND 7);

UPDATE album SET authors = CASE WHEN year <= 2025 THEN 3 ELSE 7 END;
