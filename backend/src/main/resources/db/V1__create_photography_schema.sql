CREATE TABLE country (
    id TEXT PRIMARY KEY NOT NULL,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    flag TEXT NOT NULL
) STRICT;

CREATE TABLE destination (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    country_id TEXT NOT NULL REFERENCES country (id)
) STRICT;

CREATE TABLE album (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    synopsis TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE chapter (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    destination_id TEXT NOT NULL REFERENCES destination (id),
    description TEXT NOT NULL,
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL,
    position INTEGER NOT NULL,
    source_folder TEXT NOT NULL,
    UNIQUE (album_id, position),
    UNIQUE (album_id, source_folder)
) STRICT;

CREATE TABLE photo (
    id TEXT PRIMARY KEY NOT NULL,
    chapter_id TEXT NOT NULL REFERENCES chapter (id) ON DELETE CASCADE,
    source TEXT NOT NULL,
    thumbnail TEXT,
    captured_at_ms INTEGER,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    caption TEXT,
    UNIQUE (chapter_id, source)
) STRICT;

CREATE TABLE camera (
    id TEXT PRIMARY KEY NOT NULL,
    code TEXT NOT NULL UNIQUE,
    friendly_name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('fixed_lens', 'interchangeable_lens'))
) STRICT;

CREATE TABLE lens (
    id TEXT PRIMARY KEY NOT NULL,
    camera_id TEXT NOT NULL REFERENCES camera (id),
    code TEXT NOT NULL UNIQUE,
    friendly_name TEXT NOT NULL,
    UNIQUE (camera_id, id)
) STRICT;

CREATE TABLE chapter_camera (
    chapter_id TEXT NOT NULL REFERENCES chapter (id) ON DELETE CASCADE,
    camera_id TEXT NOT NULL REFERENCES camera (id),
    use_kind TEXT NOT NULL CHECK (use_kind IN ('fixed_lens', 'interchangeable_lenses')),
    PRIMARY KEY (chapter_id, camera_id)
) STRICT;

CREATE TABLE chapter_camera_lens (
    chapter_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    lens_id TEXT NOT NULL,
    PRIMARY KEY (chapter_id, camera_id, lens_id),
    FOREIGN KEY (chapter_id, camera_id) REFERENCES chapter_camera (chapter_id, camera_id) ON DELETE CASCADE,
    FOREIGN KEY (camera_id, lens_id) REFERENCES lens (camera_id, id)
) STRICT;

CREATE TABLE spread_template (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('album_start', 'chapter_start', 'photos_only'))
) STRICT;

CREATE TABLE spread_template_slot (
    id INTEGER PRIMARY KEY,
    template_id TEXT NOT NULL REFERENCES spread_template (id),
    kind TEXT NOT NULL CHECK (kind IN ('photo', 'text')),
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL,
    UNIQUE (template_id, kind, x, y, width, height)
) STRICT;

INSERT INTO spread_template (id, name, kind) VALUES
    ('00000000-0000-0000-0000-000000000001', '2×2 L + Synopsis R', 'album_start'),
    ('00000000-0000-0000-0000-000000000002', '1 Full L + Synopsis R', 'album_start'),
    ('00000000-0000-0000-0000-000000000003', '3+2 L + Synopsis R', 'album_start'),
    ('00000000-0000-0000-0000-000000000004', 'P+2L L + Synopsis R', 'album_start');

INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES
    ('00000000-0000-0000-0000-000000000001', 'photo', 0.293982, 0.914609, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000001', 'photo', 25.199487, 0.914609, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000001', 'photo', 0.293982, 50.326646, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000001', 'photo', 25.199487, 50.326646, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000001', 'text', 50.104993, 0.914609, 49.601025, 98.170781),
    ('00000000-0000-0000-0000-000000000002', 'photo', 0.293982, 0.914609, 49.601025, 98.170781),
    ('00000000-0000-0000-0000-000000000002', 'text', 50.104993, 0.914609, 49.601025, 98.170781),
    ('00000000-0000-0000-0000-000000000003', 'photo', 0.293982, 0.914609, 16.393684, 38.870004),
    ('00000000-0000-0000-0000-000000000003', 'photo', 16.897652, 0.914609, 16.393684, 38.870004),
    ('00000000-0000-0000-0000-000000000003', 'photo', 33.501323, 0.914609, 16.393684, 38.870004),
    ('00000000-0000-0000-0000-000000000003', 'photo', 0.293982, 40.437905, 24.695519, 58.553948),
    ('00000000-0000-0000-0000-000000000003', 'photo', 25.199487, 40.437905, 24.695519, 58.553948),
    ('00000000-0000-0000-0000-000000000003', 'text', 50.104993, 0.914609, 49.601025, 98.170781),
    ('00000000-0000-0000-0000-000000000004', 'photo', 0.293982, 0.914609, 24.695519, 98.170781),
    ('00000000-0000-0000-0000-000000000004', 'photo', 25.199487, 0.914609, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000004', 'photo', 25.199487, 50.326646, 24.695519, 48.758744),
    ('00000000-0000-0000-0000-000000000004', 'text', 50.104993, 0.914609, 49.601025, 98.170781);

CREATE TABLE spread (
    id TEXT PRIMARY KEY NOT NULL,
    template_id TEXT NOT NULL REFERENCES spread_template (id),
    kind TEXT NOT NULL CHECK (kind IN ('album_start', 'chapter_start', 'photos_only')),
    album_id TEXT REFERENCES album (id) ON DELETE CASCADE,
    chapter_id TEXT REFERENCES chapter (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    CHECK (
        (kind = 'album_start' AND album_id IS NOT NULL AND chapter_id IS NULL AND position = 0)
        OR (kind = 'chapter_start' AND album_id IS NULL AND chapter_id IS NOT NULL AND position = 0)
        OR (kind = 'photos_only' AND album_id IS NULL AND chapter_id IS NOT NULL AND position > 0)
    ),
    UNIQUE (album_id, position),
    UNIQUE (chapter_id, position)
) STRICT;

CREATE TABLE spread_photo_slot (
    id TEXT PRIMARY KEY NOT NULL,
    spread_id TEXT NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE spread_photo_slot_bleed (
    slot_id TEXT NOT NULL REFERENCES spread_photo_slot (id) ON DELETE CASCADE,
    edge TEXT NOT NULL CHECK (edge IN ('top', 'bottom', 'left', 'right')),
    PRIMARY KEY (slot_id, edge)
) STRICT;

CREATE TABLE spread_text_slot (
    id TEXT PRIMARY KEY NOT NULL,
    spread_id TEXT NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE cover (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES album (id) ON DELETE CASCADE,
    spine_owner TEXT NOT NULL CHECK (spine_owner IN ('front', 'back'))
) STRICT;

CREATE TABLE cover_slot (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES cover (album_id) ON DELETE CASCADE,
    side TEXT NOT NULL CHECK (side IN ('front', 'back')),
    UNIQUE (album_id, side)
) STRICT;

CREATE TABLE placement (
    id TEXT PRIMARY KEY NOT NULL,
    photo_id TEXT NOT NULL UNIQUE REFERENCES photo (id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('spread', 'cover')),
    spread_photo_slot_id TEXT UNIQUE REFERENCES spread_photo_slot (id) ON DELETE CASCADE,
    cover_slot_id TEXT UNIQUE REFERENCES cover_slot (id) ON DELETE CASCADE,
    shift_x REAL NOT NULL,
    shift_y REAL NOT NULL,
    mirrored INTEGER NOT NULL CHECK (mirrored IN (0, 1)),
    zoom REAL NOT NULL,
    caption_corner TEXT,
    CHECK (
        (kind = 'spread'
            AND spread_photo_slot_id IS NOT NULL
            AND cover_slot_id IS NULL
            AND caption_corner IS NOT NULL
            AND caption_corner IN ('top_left', 'top_right', 'bottom_left', 'bottom_right'))
        OR (kind = 'cover'
            AND spread_photo_slot_id IS NULL
            AND cover_slot_id IS NOT NULL
            AND caption_corner IS NULL)
    )
) STRICT;

CREATE TABLE editor_session (
    id TEXT PRIMARY KEY NOT NULL,
    owner TEXT NOT NULL UNIQUE,
    last_heartbeat_ms INTEGER NOT NULL
) STRICT;

CREATE TABLE spread_editing_lock (
    spread_id TEXT PRIMARY KEY NOT NULL REFERENCES spread (id) ON DELETE CASCADE,
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;

CREATE TABLE cover_editing_lock (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES cover (album_id) ON DELETE CASCADE,
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;
