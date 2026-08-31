import { HttpError, json } from "./http";
import type { Env, SessionUser } from "./types";

const MAX_STATISTICS_BYTES = 512 * 1024;
const MAX_MEDIA_PER_LIST = 20;
const MAX_MEDIA_ID_LENGTH = 512;
const MAX_MEDIA_TITLE_LENGTH = 512;
const MAX_MEDIA_SUBTITLE_LENGTH = 1024;
const MAX_ARTWORK_URL_LENGTH = 4096;
const MAX_BUCKET_LABEL_LENGTH = 64;
const MAX_CLIENT_CLOCK_SKEW_MS = 60_000;

const RANGE_DAILY_LIMITS = {
  DAYS_7: 7,
  MONTH_1: 32,
  MONTHS_3: 93,
  MONTHS_6: 186,
  YEAR_1: 367,
  ALL_TIME: 4000,
} as const;

type StatisticsRange = keyof typeof RANGE_DAILY_LIMITS;

interface StatisticsSnapshot {
  updatedAt: number;
  ranges: Record<StatisticsRange, StatisticsRangeSnapshot>;
}

interface StatisticsRangeSnapshot {
  summary: StatisticsSummary;
  favoriteArtist: StatisticsMedia | null;
  favoriteSong: StatisticsMedia | null;
  dailyListening: StatisticsBucket[];
  listeningByDayOfWeek: StatisticsBucket[];
  listeningByHour: StatisticsBucket[];
  topSongs: StatisticsMedia[];
  topArtists: StatisticsMedia[];
  topAlbums: StatisticsMedia[];
}

interface StatisticsSummary {
  totalListeningMillis: number;
  totalPlays: number;
  uniqueSongs: number;
  uniqueArtists: number;
  uniqueAlbums: number;
}

interface StatisticsMedia {
  id: string;
  title: string;
  subtitle: string | null;
  artworkUrl: string | null;
  listeningMillis: number;
  playCount: number;
}

interface StatisticsBucket {
  label: string;
  listeningMillis: number;
}

export async function putStatistics(request: Request, env: Env, user: SessionUser): Promise<Response> {
  const raw = await readLimitedJsonBody(request);
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    throw new HttpError(400, "invalid statistics snapshot");
  }

  const snapshot = normalizeSnapshot(value);
  const canonicalJson = JSON.stringify(snapshot);
  if (new TextEncoder().encode(canonicalJson).byteLength > MAX_STATISTICS_BYTES) {
    throw new HttpError(413, "statistics snapshot is too large");
  }

  await env.DB.prepare(
    `INSERT INTO user_statistics(user_id, snapshot_json, updated_at)
     VALUES (?, ?, ?)
     ON CONFLICT(user_id) DO UPDATE SET
       snapshot_json = excluded.snapshot_json,
       updated_at = excluded.updated_at
     WHERE excluded.updated_at >= user_statistics.updated_at`,
  ).bind(user.id, canonicalJson, snapshot.updatedAt).run();

  const stored = await env.DB.prepare(
    "SELECT updated_at FROM user_statistics WHERE user_id = ?",
  ).bind(user.id).first<{ updated_at: number }>();
  return json({ updatedAt: stored?.updated_at ?? snapshot.updatedAt });
}

export async function getFriendStatistics(
  env: Env,
  user: SessionUser,
  friendId: string,
): Promise<Response> {
  // Keep the friendship/privacy decision and the snapshot read in one D1 query so
  // a caller cannot use this endpoint to probe whether arbitrary users have data.
  const [low, high] = pair(user.id, friendId);
  const row = await env.DB.prepare(
    `SELECT s.snapshot_json
     FROM user_statistics s
     JOIN friendships f ON f.user_low = ? AND f.user_high = ?
     WHERE s.user_id = ?
       AND NOT EXISTS (
         SELECT 1 FROM blocks b
         WHERE (b.blocker_id = ? AND b.blocked_id = ?)
            OR (b.blocker_id = ? AND b.blocked_id = ?)
       )
     LIMIT 1`,
  ).bind(low, high, friendId, user.id, friendId, friendId, user.id).first<{ snapshot_json: string }>();
  if (!row) throw new HttpError(404, "friend statistics not found");

  let statistics: unknown;
  try {
    statistics = JSON.parse(row.snapshot_json);
  } catch {
    throw new HttpError(500, "stored statistics snapshot is invalid");
  }
  return json({ statistics });
}

async function readLimitedJsonBody(request: Request): Promise<string> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    throw new HttpError(415, "expected application/json");
  }

  const declaredLength = request.headers.get("content-length");
  if (declaredLength !== null) {
    const bytes = Number(declaredLength);
    if (!Number.isSafeInteger(bytes) || bytes < 0) throw new HttpError(400, "invalid content length");
    if (bytes > MAX_STATISTICS_BYTES) throw new HttpError(413, "statistics snapshot is too large");
  }

  if (!request.body) throw new HttpError(400, "invalid statistics snapshot");
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    total += value.byteLength;
    if (total > MAX_STATISTICS_BYTES) {
      await reader.cancel().catch(() => undefined);
      throw new HttpError(413, "statistics snapshot is too large");
    }
    chunks.push(value);
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new HttpError(400, "invalid statistics snapshot");
  }
}

function normalizeSnapshot(value: unknown): StatisticsSnapshot {
  if (!isRecord(value)) throw new HttpError(400, "invalid statistics snapshot");
  const updatedAt = requireNonNegativeInteger(value.updatedAt, "invalid statistics timestamp");
  if (updatedAt === 0) throw new HttpError(400, "invalid statistics timestamp");
  if (!isRecord(value.ranges)) throw new HttpError(400, "invalid statistics ranges");
  const rangeValues = value.ranges;

  const rangeKeys = Object.keys(rangeValues);
  const expectedRanges = Object.keys(RANGE_DAILY_LIMITS) as StatisticsRange[];
  if (rangeKeys.length !== expectedRanges.length || expectedRanges.some((range) => !(range in rangeValues))) {
    throw new HttpError(400, "invalid statistics ranges");
  }

  const ranges = {} as Record<StatisticsRange, StatisticsRangeSnapshot>;
  for (const range of expectedRanges) {
    ranges[range] = normalizeRange(rangeValues[range], RANGE_DAILY_LIMITS[range]);
  }

  return {
    updatedAt: Math.min(updatedAt, Date.now() + MAX_CLIENT_CLOCK_SKEW_MS),
    ranges,
  };
}

function normalizeRange(value: unknown, maxDailyBuckets: number): StatisticsRangeSnapshot {
  if (!isRecord(value) || !isRecord(value.summary)) throw new HttpError(400, "invalid statistics range");
  return {
    summary: {
      totalListeningMillis: requireNonNegativeInteger(value.summary.totalListeningMillis),
      totalPlays: requireNonNegativeInteger(value.summary.totalPlays),
      uniqueSongs: requireNonNegativeInteger(value.summary.uniqueSongs),
      uniqueArtists: requireNonNegativeInteger(value.summary.uniqueArtists),
      uniqueAlbums: requireNonNegativeInteger(value.summary.uniqueAlbums),
    },
    favoriteArtist: normalizeNullableMedia(value.favoriteArtist),
    favoriteSong: normalizeNullableMedia(value.favoriteSong),
    dailyListening: normalizeBuckets(value.dailyListening, maxDailyBuckets),
    listeningByDayOfWeek: normalizeBuckets(value.listeningByDayOfWeek, 7),
    listeningByHour: normalizeBuckets(value.listeningByHour, 24),
    topSongs: normalizeMediaList(value.topSongs),
    topArtists: normalizeMediaList(value.topArtists),
    topAlbums: normalizeMediaList(value.topAlbums),
  };
}

function normalizeBuckets(value: unknown, max: number): StatisticsBucket[] {
  if (!Array.isArray(value) || value.length > max) throw new HttpError(400, "invalid statistics buckets");
  return value.map((bucket) => {
    if (!isRecord(bucket) || typeof bucket.label !== "string" || bucket.label.length > MAX_BUCKET_LABEL_LENGTH) {
      throw new HttpError(400, "invalid statistics bucket");
    }
    return {
      label: bucket.label,
      listeningMillis: requireNonNegativeInteger(bucket.listeningMillis),
    };
  });
}

function normalizeMediaList(value: unknown): StatisticsMedia[] {
  if (!Array.isArray(value) || value.length > MAX_MEDIA_PER_LIST) {
    throw new HttpError(400, "invalid statistics media");
  }
  return value.map(normalizeMedia);
}

function normalizeNullableMedia(value: unknown): StatisticsMedia | null {
  if (value === null || value === undefined) return null;
  return normalizeMedia(value);
}

function normalizeMedia(value: unknown): StatisticsMedia {
  if (!isRecord(value)) throw new HttpError(400, "invalid statistics media");
  if (
    typeof value.id !== "string" ||
    value.id.length > MAX_MEDIA_ID_LENGTH ||
    typeof value.title !== "string" ||
    value.title.length > MAX_MEDIA_TITLE_LENGTH
  ) {
    throw new HttpError(400, "invalid statistics media");
  }
  const subtitle = optionalString(value.subtitle, MAX_MEDIA_SUBTITLE_LENGTH);
  const artworkUrl = optionalString(value.artworkUrl, MAX_ARTWORK_URL_LENGTH);
  if (artworkUrl !== null && !isSafeArtworkUrl(artworkUrl)) {
    throw new HttpError(400, "invalid statistics artwork url");
  }
  return {
    id: value.id,
    title: value.title,
    subtitle,
    artworkUrl,
    listeningMillis: requireNonNegativeInteger(value.listeningMillis),
    playCount: requireNonNegativeInteger(value.playCount),
  };
}

function optionalString(value: unknown, maxLength: number): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value !== "string" || value.length > maxLength) throw new HttpError(400, "invalid statistics media");
  return value;
}

function requireNonNegativeInteger(value: unknown, message = "invalid statistics value"): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new HttpError(400, message);
  return value;
}

function isSafeArtworkUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}

function pair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
