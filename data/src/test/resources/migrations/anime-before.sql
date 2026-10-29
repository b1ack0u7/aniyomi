CREATE TABLE anime_sync(
    _id INTEGER NOT NULL PRIMARY KEY,
    anime_id INTEGER NOT NULL,
    sync_id INTEGER NOT NULL,
    remote_id INTEGER NOT NULL,
    library_id INTEGER,
    title TEXT NOT NULL,
    last_episode_seen REAL NOT NULL,
    total_episodes INTEGER NOT NULL,
    status INTEGER NOT NULL,
    score REAL NOT NULL,
    remote_url TEXT NOT NULL,
    start_date INTEGER NOT NULL,
    finish_date INTEGER NOT NULL,
    private INTEGER DEFAULT 0 NOT NULL,
    UNIQUE (anime_id, sync_id) ON CONFLICT REPLACE,
    FOREIGN KEY(anime_id) REFERENCES animes (_id)
    ON DELETE CASCADE
);
-- statement-break
CREATE TABLE animehistory(
    _id INTEGER NOT NULL PRIMARY KEY,
    episode_id INTEGER NOT NULL UNIQUE,
    last_seen INTEGER,
    FOREIGN KEY(episode_id) REFERENCES episodes (_id)
    ON DELETE CASCADE
);
-- statement-break
CREATE INDEX animehistory_history_episode_id_index ON animehistory(episode_id);
-- statement-break
CREATE TABLE animes(
    _id INTEGER NOT NULL PRIMARY KEY,
    source INTEGER NOT NULL,
    url TEXT NOT NULL,
    artist TEXT,
    author TEXT,
    description TEXT,
    genre TEXT,
    title TEXT NOT NULL,
    status INTEGER NOT NULL,
    thumbnail_url TEXT,
    favorite INTEGER NOT NULL,
    last_update INTEGER,
    next_update INTEGER,
    initialized INTEGER NOT NULL,
    viewer INTEGER NOT NULL,
    episode_flags INTEGER NOT NULL,
    cover_last_modified INTEGER NOT NULL,
    date_added INTEGER NOT NULL,
    update_strategy INTEGER NOT NULL DEFAULT 0,
    calculate_interval INTEGER DEFAULT 0 NOT NULL,
    last_modified_at INTEGER NOT NULL DEFAULT 0,
    favorite_modified_at INTEGER,
    version INTEGER NOT NULL DEFAULT 0,
    is_syncing INTEGER NOT NULL DEFAULT 0,
    fetch_type INTEGER NOT NULL DEFAULT 1,
    parent_id INTEGER,
    season_flags INTEGER NOT NULL,
    season_number REAL NOT NULL,
    season_source_order INTEGER NOT NULL,
    background_url TEXT,
    background_last_modified INTEGER NOT NULL
);
-- statement-break
CREATE INDEX animelib_favorite_index ON animes(favorite) WHERE favorite = 1;
-- statement-break
CREATE INDEX animes_url_index ON animes(url);
-- statement-break
CREATE INDEX animes_parent_id ON animes(parent_id);
-- statement-break
CREATE INDEX animes_fetch_type ON animes(fetch_type);
-- statement-break
CREATE TRIGGER update_last_favorite_at_animes
AFTER UPDATE OF favorite ON animes
BEGIN
  UPDATE animes
  SET favorite_modified_at = strftime('%s', 'now')
  WHERE _id = new._id;
END;
-- statement-break
CREATE TRIGGER update_last_modified_at_animes
AFTER UPDATE ON animes
FOR EACH ROW
BEGIN
  UPDATE animes
  SET last_modified_at = strftime('%s', 'now')
  WHERE _id = new._id;
END;
-- statement-break
CREATE TRIGGER update_anime_version AFTER UPDATE ON animes
BEGIN
    UPDATE animes SET version = version + 1
    WHERE _id = new._id AND new.is_syncing = 0 AND (
        new.url != old.url OR
        new.description != old.description OR
        new.favorite != old.favorite
    );
END;
-- statement-break
CREATE TABLE animes_categories(
    _id INTEGER NOT NULL PRIMARY KEY,
    anime_id INTEGER NOT NULL,
    category_id INTEGER NOT NULL,
    FOREIGN KEY(category_id) REFERENCES categories (_id)
    ON DELETE CASCADE,
    FOREIGN KEY(anime_id) REFERENCES animes (_id)
    ON DELETE CASCADE
);
-- statement-break
CREATE TRIGGER insert_anime_category_update_version AFTER INSERT ON animes_categories
BEGIN
    UPDATE animes
    SET version = version + 1
    WHERE _id = new.anime_id AND (SELECT is_syncing FROM animes WHERE _id = new.anime_id) = 0;
END;
-- statement-break
CREATE TABLE animesources(
    _id INTEGER NOT NULL PRIMARY KEY,
    lang TEXT NOT NULL,
    name TEXT NOT NULL
);
-- statement-break
CREATE TABLE categories(
    _id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    sort INTEGER NOT NULL,
    flags INTEGER NOT NULL,
    hidden INTEGER NOT NULL DEFAULT 0
);
-- statement-break
-- Insert system category
INSERT OR IGNORE INTO categories(_id, name, sort, flags) VALUES (0, "", -1, 0);
-- statement-break
-- Disallow deletion of default category
CREATE TRIGGER IF NOT EXISTS system_category_delete_trigger BEFORE DELETE
ON categories
BEGIN SELECT CASE
    WHEN old._id <= 0 THEN
        RAISE(ABORT, "System category can't be deleted")
    END;
END;
-- statement-break
CREATE TABLE custom_buttons (
    _id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    isFavorite INTEGER NOT NULL,
    sortIndex INTEGER NOT NULL,
    content TEXT NOT NULL,
    longPressContent TEXT NOT NULL,
    onStartup TEXT NOT NULL
);
-- statement-break
-- Insert skip intro button --
INSERT OR IGNORE INTO custom_buttons (_id, name, isFavorite, sortIndex, content, longPressContent, onStartup)
VALUES (1, '+85 s', 1, 0, 'local intro_length = mp.get_property_number("user-data/current-anime/intro-length")
aniyomi.right_seek_by(intro_length)', 'aniyomi.int_picker("Change intro length", "%ds", 0, 255, 1, "user-data/current-anime/intro-length")', 'function update_button(_, length)
  if length ~= nil then
    if length == 0 then
	  aniyomi.hide_button()
	  return
	else
	  aniyomi.show_button()
	end
    aniyomi.set_button_title("+" .. length .. " s")
  end
end

if $isPrimary then
  mp.observe_property("user-data/current-anime/intro-length", "number", update_button)
end');
-- statement-break
CREATE TABLE episodes(
    _id INTEGER NOT NULL PRIMARY KEY,
    anime_id INTEGER NOT NULL,
    url TEXT NOT NULL,
    name TEXT NOT NULL,
    scanlator TEXT,
    seen INTEGER NOT NULL,
    bookmark INTEGER NOT NULL,
    last_second_seen INTEGER NOT NULL,
    total_seconds INTEGER NOT NULL,
    episode_number REAL NOT NULL,
    source_order INTEGER NOT NULL,
    date_fetch INTEGER NOT NULL,
    date_upload INTEGER NOT NULL,
    last_modified_at INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 0,
    is_syncing INTEGER NOT NULL DEFAULT 0,
    summary TEXT,
    preview_url TEXT,
    fillermark INTEGER NOT NULL,
    FOREIGN KEY(anime_id) REFERENCES animes (_id)
    ON DELETE CASCADE
);
-- statement-break
CREATE INDEX episodes_anime_id_index ON episodes(anime_id);
-- statement-break
CREATE INDEX episodes_unseen_by_anime_index ON episodes(anime_id, seen) WHERE seen = 0;
-- statement-break
CREATE TRIGGER update_last_modified_at_episodes
AFTER UPDATE ON episodes
FOR EACH ROW
BEGIN
  UPDATE episodes
  SET last_modified_at = strftime('%s', 'now')
  WHERE _id = new._id;
END;
-- statement-break
CREATE TRIGGER update_episode_and_anime_version AFTER UPDATE ON episodes
WHEN new.is_syncing = 0 AND (
    new.seen != old.seen OR
    new.bookmark != old.bookmark OR
    new.last_second_seen != old.last_second_seen
)
BEGIN
    -- Update the episode version
    UPDATE episodes SET version = version + 1
    WHERE _id = new._id;

    -- Update the anime version
    UPDATE animes SET version = version + 1
    WHERE _id = new.anime_id AND (SELECT is_syncing FROM animes WHERE _id = new.anime_id) = 0;
END;
-- statement-break
CREATE TABLE extension_repos (
    base_url TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    short_name TEXT,
    website TEXT NOT NULL,
    signing_key_fingerprint TEXT UNIQUE NOT NULL
);
-- statement-break
CREATE VIEW animedeletableView AS
SELECT
    M._id,
    M.source,
    M.fetch_type
FROM animes AS M
WHERE M.favorite = 0
AND M.parent_id IS NULL;
-- statement-break
CREATE VIEW animehistoryView AS
SELECT
    animehistory._id AS id,
    animes._id AS animeId,
    episodes._id AS episodeId,
    animes.title,
    animes.thumbnail_url AS thumbnailUrl,
    animes.source,
    animes.favorite,
    animes.cover_last_modified,
    episodes.episode_number AS episodeNumber,
    animehistory.last_seen AS seenAt,
    max_last_seen.last_seen AS maxSeenAt,
    max_last_seen.episode_id AS maxSeenAtEpisodeId
FROM animes
JOIN episodes
ON animes._id = episodes.anime_id
JOIN animehistory
ON episodes._id = animehistory.episode_id
JOIN (
    SELECT episodes.anime_id,episodes._id AS episode_id, MAX(animehistory.last_seen) AS last_seen
    FROM episodes JOIN animehistory
    ON episodes._id = animehistory.episode_id
    GROUP BY episodes.anime_id
) AS max_last_seen
ON episodes.anime_id = max_last_seen.anime_id;
-- statement-break
CREATE VIEW animehistorystatsView AS
SELECT
    episodes.anime_id,
    max(ah.last_seen) AS lastSeen
FROM episodes
LEFT JOIN animehistory AS ah ON episodes._id = ah.episode_id
GROUP BY episodes.anime_id;
-- statement-break
CREATE VIEW animelibView AS
SELECT
    M.*,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.total, 0)
        WHEN 0 THEN coalesce(ASS.child_count, 0)
    END AS totalCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.seenCount, 0)
        WHEN 0 THEN coalesce(ASS.fully_seen_seasons, 0)
    END AS seenCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.latestUpload, 0)
        WHEN 0 THEN coalesce(ASS.max_latest_upload, 0)
    END AS latestUpload,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.fetchedAt, 0)
        WHEN 0 THEN coalesce(ASS.max_fetched_at, 0)
    END AS episodeFetchedAt,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(AHS.lastSeen, 0)
        WHEN 0 THEN coalesce(ASS.max_last_seen, 0)
    END AS lastSeen,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.bookmarkCount, 0)
        WHEN 0 THEN coalesce(ASS.total_bookmarks, 0)
    END AS bookmarkCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.fillermarkCount, 0)
        WHEN 0 THEN coalesce(ASS.total_fillermarks, 0)
    END AS fillermarkCount,
    coalesce(MC.category_id, 0) AS category
FROM animes M
LEFT JOIN episodestatsView AS ES ON M._id = ES.anime_id
LEFT JOIN animehistorystatsView AS AHS ON M._id = AHS.anime_id
LEFT JOIN animes_categories AS MC ON MC.anime_id = M._id
LEFT JOIN animeseasonstatsView AS ASS ON M._id = ASS.parent_id
WHERE M.favorite = 1;
-- statement-break
CREATE VIEW animeseasonsView AS
SELECT
    M.*,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.total, 0)
        WHEN 0 THEN coalesce(ASS.child_count, 0)
    END AS totalCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.seenCount, 0)
        WHEN 0 THEN coalesce(ASS.fully_seen_seasons, 0)
    END AS seenCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.latestUpload, 0)
        WHEN 0 THEN coalesce(ASS.max_latest_upload, 0)
    END AS latestUpload,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.fetchedAt, 0)
        WHEN 0 THEN coalesce(ASS.max_fetched_at, 0)
    END AS episodeFetchedAt,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(AHS.lastSeen, 0)
        WHEN 0 THEN coalesce(ASS.max_last_seen, 0)
    END AS lastSeen,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.bookmarkCount, 0)
        WHEN 0 THEN coalesce(ASS.total_bookmarks, 0)
    END AS bookmarkCount,
    CASE M.fetch_type
        WHEN 1 THEN coalesce(ES.fillermarkCount, 0)
        WHEN 0 THEN coalesce(ASS.total_fillermarks, 0)
    END AS fillermarkCount
FROM animes AS M
LEFT JOIN episodestatsView AS ES
ON M._id = ES.anime_id
LEFT JOIN animehistorystatsView AS AHS
ON M._id = AHS.anime_id
LEFT JOIN animeseasonstatsView AS ASS
ON M._id = ASS.parent_id
WHERE M.parent_id IS NOT NULL;
-- statement-break
CREATE VIEW animeseasonstatsView AS
SELECT
    season.parent_id,
    count(*) AS child_count,
    sum(CASE WHEN season.fetch_type = 1 AND
                 coalesce(ES.total, 0) = coalesce(ES.seenCount, 0)
            THEN 1 ELSE 0 END) AS fully_seen_seasons,
    max(coalesce(ES.latestUpload, 0)) AS max_latest_upload,
    max(coalesce(ES.fetchedAt, 0)) AS max_fetched_at,
    max(coalesce(AHS.lastSeen, 0)) AS max_last_seen,
    sum(coalesce(ES.bookmarkCount, 0)) AS total_bookmarks,
    sum(coalesce(ES.fillermarkCount, 0)) AS total_fillermarks
FROM animes season
LEFT JOIN episodestatsView ES ON season._id = ES.anime_id
LEFT JOIN animehistorystatsView AHS ON season._id = AHS.anime_id
WHERE season.parent_id IS NOT NULL
GROUP BY season.parent_id;
-- statement-break
CREATE VIEW animeupdatesView AS
SELECT
    animes._id AS animeId,
    animes.title AS animeTitle,
    episodes._id AS episodeId,
    episodes.name AS episodeName,
    episodes.scanlator,
    episodes.seen,
    episodes.bookmark,
    episodes.fillermark,
    episodes.last_second_seen,
    episodes.total_seconds AS totalSeconds,
    animes.source,
    animes.favorite,
    animes.thumbnail_url AS thumbnailUrl,
    animes.cover_last_modified AS coverLastModified,
    episodes.date_upload AS dateUpload,
    episodes.date_fetch AS datefetch
FROM animes JOIN episodes
ON animes._id = episodes.anime_id
WHERE favorite = 1
AND date_fetch > date_added
ORDER BY date_fetch DESC;
-- statement-break
CREATE VIEW episodestatsView AS
SELECT
    episodes.anime_id,
    count(*) AS total,
    sum(seen) AS seenCount,
    coalesce(max(episodes.date_upload), 0) AS latestUpload,
    coalesce(max(episodes.date_fetch), 0) AS fetchedAt,
    sum(episodes.bookmark) AS bookmarkCount,
    sum(episodes.fillermark) AS fillermarkCount
FROM episodes
GROUP BY episodes.anime_id;
