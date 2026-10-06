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
    synopsis TEXT NOT NULL
) STRICT;

CREATE TABLE chapter (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES album (id),
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
    chapter_id TEXT NOT NULL REFERENCES chapter (id),
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
    chapter_id TEXT NOT NULL REFERENCES chapter (id),
    camera_id TEXT NOT NULL REFERENCES camera (id),
    use_kind TEXT NOT NULL CHECK (use_kind IN ('fixed_lens', 'interchangeable_lenses')),
    PRIMARY KEY (chapter_id, camera_id)
) STRICT;

CREATE TABLE chapter_camera_lens (
    chapter_id TEXT NOT NULL,
    camera_id TEXT NOT NULL,
    lens_id TEXT NOT NULL,
    PRIMARY KEY (chapter_id, camera_id, lens_id),
    FOREIGN KEY (chapter_id, camera_id) REFERENCES chapter_camera (chapter_id, camera_id),
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

CREATE TABLE spread (
    id TEXT PRIMARY KEY NOT NULL,
    template_id TEXT NOT NULL REFERENCES spread_template (id),
    kind TEXT NOT NULL CHECK (kind IN ('album_start', 'chapter_start', 'photos_only')),
    album_id TEXT REFERENCES album (id),
    chapter_id TEXT REFERENCES chapter (id),
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
    spread_id TEXT NOT NULL REFERENCES spread (id),
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE spread_photo_slot_bleed (
    slot_id TEXT NOT NULL REFERENCES spread_photo_slot (id),
    edge TEXT NOT NULL CHECK (edge IN ('top', 'bottom', 'left', 'right')),
    PRIMARY KEY (slot_id, edge)
) STRICT;

CREATE TABLE spread_text_slot (
    id TEXT PRIMARY KEY NOT NULL,
    spread_id TEXT NOT NULL REFERENCES spread (id),
    x REAL NOT NULL,
    y REAL NOT NULL,
    width REAL NOT NULL,
    height REAL NOT NULL
) STRICT;

CREATE TABLE cover (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES album (id),
    spine_owner TEXT NOT NULL CHECK (spine_owner IN ('front', 'back'))
) STRICT;

CREATE TABLE cover_slot (
    id TEXT PRIMARY KEY NOT NULL,
    album_id TEXT NOT NULL REFERENCES cover (album_id),
    side TEXT NOT NULL CHECK (side IN ('front', 'back')),
    UNIQUE (album_id, side)
) STRICT;

CREATE TABLE placement (
    id TEXT PRIMARY KEY NOT NULL,
    photo_id TEXT NOT NULL UNIQUE REFERENCES photo (id),
    kind TEXT NOT NULL CHECK (kind IN ('spread', 'cover')),
    spread_photo_slot_id TEXT UNIQUE REFERENCES spread_photo_slot (id),
    cover_slot_id TEXT UNIQUE REFERENCES cover_slot (id),
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
    spread_id TEXT PRIMARY KEY NOT NULL REFERENCES spread (id),
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;

CREATE TABLE cover_editing_lock (
    album_id TEXT PRIMARY KEY NOT NULL REFERENCES cover (album_id),
    session_id TEXT NOT NULL REFERENCES editor_session (id)
) STRICT;
