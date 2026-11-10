-- Offline timetable releases. Files live in R2 at <version>/<name>.
-- A release becomes visible to apps only once published_at is set, after every file is uploaded.
CREATE TABLE bundle_releases (
  version TEXT PRIMARY KEY CHECK (version GLOB '[0-9A-Za-z_.-]*'),
  schema_version INTEGER NOT NULL DEFAULT 1,
  feed_start TEXT NOT NULL,
  feed_end TEXT NOT NULL CHECK (feed_end >= feed_start),
  built_at TEXT NOT NULL,
  -- JSON array: [{ "name": "timetable.bin", "size": 123, "sha256": "…" }]
  files TEXT NOT NULL CHECK (json_valid(files) AND json_type(files) = 'array'),
  -- Oldest Android versionCode that can decode this release.
  min_android_version INTEGER NOT NULL DEFAULT 1,
  published_at TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX bundle_releases_published ON bundle_releases (published_at DESC) WHERE published_at IS NOT NULL;
