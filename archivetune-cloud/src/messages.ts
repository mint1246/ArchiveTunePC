import { HttpError, json } from "./http";
import { areFriends } from "./social";
import type { Env, SessionUser } from "./types";

const MAX_TEXT_CHARS = 4_000;
const MAX_SONG_BYTES = 16 * 1024;
const MAX_COLLECTION_BYTES = 256 * 1024;
const MAX_QUEUE_ITEMS = 50;
const MAX_PLAYLIST_ITEMS = 200;
const MAX_HISTORY_LIMIT = 100;
const MAX_REQUEST_BYTES = MAX_COLLECTION_BYTES + 16 * 1024;

type MessageKind = "text" | "song" | "queue" | "playlist";
type JsonObject = Record<string, unknown>;

interface MessageRow {
  id: number;
  sender_id: string;
  recipient_id: string;
  kind: MessageKind;
  payload_json: string;
  created_at: number;
}

interface FriendMessage {
  id: number;
  senderId: string;
  recipientId: string;
  kind: MessageKind;
  payload: JsonObject;
  createdAt: number;
}

interface SharedTrack extends JsonObject {
  id: string;
  title: string;
  artists: Array<{ id?: string; name: string }>;
  albumId?: string;
  albumTitle?: string;
  artworkUri?: string;
  durationHintMs?: number;
  explicit?: boolean;
  isMusicVideo?: boolean;
}

export async function sendFriendMessage(
  request: Request,
  env: Env,
  user: SessionUser,
  friendId: string,
): Promise<Response> {
  await requireFriendship(env, user.id, friendId);
  const rawBody = await readLimitedJsonBody(request);
  let body: { kind?: unknown; payload?: unknown };
  try {
    body = JSON.parse(rawBody) as { kind?: unknown; payload?: unknown };
  } catch {
    throw new HttpError(400, "invalid json");
  }
  const kind = parseKind(body.kind);
  const payload = sanitizePayload(kind, body.payload);
  const payloadJson = JSON.stringify(payload);
  const maxBytes = kind === "song" ? MAX_SONG_BYTES : kind === "text" ? MAX_TEXT_CHARS * 4 + 256 : MAX_COLLECTION_BYTES;
  if (utf8Length(payloadJson) > maxBytes) throw new HttpError(413, "message payload too large");

  const [low, high] = pair(user.id, friendId);
  const createdAt = Date.now();
  const result = await env.DB.prepare(
    `INSERT INTO friend_messages(
       sender_id, recipient_id, conversation_low, conversation_high, kind, payload_json, created_at
     )
     SELECT ?, ?, ?, ?, ?, ?, ?
     WHERE EXISTS (
       SELECT 1 FROM friendships WHERE user_low = ? AND user_high = ?
     )`,
  ).bind(user.id, friendId, low, high, kind, payloadJson, createdAt, low, high).run();

  if (result.meta.changes !== 1) throw new HttpError(403, "messages require friendship");
  const id = result.meta.last_row_id;
  if (typeof id !== "number") throw new Error("D1 did not return a message id");
  const message: FriendMessage = {
    id,
    senderId: user.id,
    recipientId: friendId,
    kind,
    payload,
    createdAt,
  };
  await pushMessage(env, friendId, message);
  return json({ message }, 201);
}

export async function getFriendMessages(
  request: Request,
  env: Env,
  user: SessionUser,
  friendId: string,
): Promise<Response> {
  await requireFriendship(env, user.id, friendId);
  const url = new URL(request.url);
  const limit = parseLimit(url.searchParams.get("limit"));
  const before = parseBefore(url.searchParams.get("before"));
  const [low, high] = pair(user.id, friendId);

  const statement = before === null
    ? env.DB.prepare(
      `SELECT id, sender_id, recipient_id, kind, payload_json, created_at
       FROM friend_messages
       WHERE conversation_low = ? AND conversation_high = ?
         AND EXISTS (SELECT 1 FROM friendships WHERE user_low = ? AND user_high = ?)
       ORDER BY id DESC
       LIMIT ?`,
    ).bind(low, high, low, high, limit)
    : env.DB.prepare(
      `SELECT id, sender_id, recipient_id, kind, payload_json, created_at
       FROM friend_messages
       WHERE conversation_low = ? AND conversation_high = ? AND id < ?
         AND EXISTS (SELECT 1 FROM friendships WHERE user_low = ? AND user_high = ?)
       ORDER BY id DESC
       LIMIT ?`,
    ).bind(low, high, before, low, high, limit);

  const rows = await statement.all<MessageRow>();
  const messages = rows.results.map(messageFromRow).reverse();
  return json({ messages });
}

function sanitizePayload(kind: MessageKind, value: unknown): JsonObject {
  const payload = requireObject(value, "payload");
  switch (kind) {
    case "text": {
      const text = requireString(payload.text, "text", MAX_TEXT_CHARS, false);
      if (!text.trim()) throw new HttpError(400, "text cannot be empty");
      return { text };
    }
    case "song":
      return sanitizeSharedTrack(payload);
    case "queue": {
      const rawItems = requireArray(payload.items, "items", 1, MAX_QUEUE_ITEMS);
      const result: JsonObject = { items: rawItems.map((item) => sanitizeSharedTrack(item)) };
      const title = optionalString(payload.title, "title", 256, true);
      if (title !== undefined) result.title = title;
      if (payload.currentIndex !== undefined) {
        if (
          typeof payload.currentIndex !== "number" ||
          !Number.isInteger(payload.currentIndex) ||
          payload.currentIndex < 0 ||
          payload.currentIndex >= rawItems.length
        ) {
          throw new HttpError(400, "invalid queue current index");
        }
        result.currentIndex = payload.currentIndex;
      }
      return result;
    }
    case "playlist": {
      const id = optionalString(payload.id, "id", 256, true);
      const title = requireString(payload.title, "title", 256, true);
      const rawItems = payload.items === undefined
        ? []
        : requireArray(payload.items, "items", 0, MAX_PLAYLIST_ITEMS);
      if (!id && rawItems.length === 0) throw new HttpError(400, "playlist requires id or items");
      const result: JsonObject = {
        title,
        items: rawItems.map((item) => sanitizeSharedTrack(item)),
      };
      if (id !== undefined) result.id = id;
      const artworkUri = optionalHttpsUrl(payload.artworkUri, "artworkUri");
      if (artworkUri !== undefined) result.artworkUri = artworkUri;
      return result;
    }
  }
}

function sanitizeSharedTrack(value: unknown): SharedTrack {
  const track = requireObject(value, "track");
  const result: SharedTrack = {
    id: requireString(track.id, "track id", 256, true),
    title: requireString(track.title, "track title", 512, true),
    artists: [],
  };
  if (track.artists !== undefined) {
    const artists = requireArray(track.artists, "artists", 0, 16);
    result.artists = artists.map((entry) => {
      const artist = requireObject(entry, "artist");
      const artistResult: { id?: string; name: string } = {
        name: requireString(artist.name, "artist name", 256, true),
      };
      const id = optionalString(artist.id, "artist id", 256, true);
      if (id !== undefined) artistResult.id = id;
      return artistResult;
    });
  }
  const albumId = optionalString(track.albumId, "album id", 256, true);
  if (albumId !== undefined) result.albumId = albumId;
  const albumTitle = optionalString(track.albumTitle, "album title", 512, true);
  if (albumTitle !== undefined) result.albumTitle = albumTitle;
  const artworkUri = optionalHttpsUrl(track.artworkUri, "artworkUri");
  if (artworkUri !== undefined) result.artworkUri = artworkUri;
  if (track.durationHintMs !== undefined && track.durationHintMs !== null) {
    if (typeof track.durationHintMs !== "number" || !Number.isFinite(track.durationHintMs) || track.durationHintMs < 0 || track.durationHintMs > 86_400_000) {
      throw new HttpError(400, "invalid track duration");
    }
    result.durationHintMs = Math.round(track.durationHintMs);
  }
  if (track.explicit !== undefined) {
    if (typeof track.explicit !== "boolean") throw new HttpError(400, "invalid explicit flag");
    result.explicit = track.explicit;
  }
  if (track.isMusicVideo !== undefined) {
    if (typeof track.isMusicVideo !== "boolean") throw new HttpError(400, "invalid music video flag");
    result.isMusicVideo = track.isMusicVideo;
  }
  return result;
}

function messageFromRow(row: MessageRow): FriendMessage {
  const payload = JSON.parse(row.payload_json) as unknown;
  if (!isRecord(payload)) throw new Error("invalid stored message payload");
  return {
    id: row.id,
    senderId: row.sender_id,
    recipientId: row.recipient_id,
    kind: row.kind,
    payload,
    createdAt: row.created_at,
  };
}

async function requireFriendship(env: Env, userId: string, friendId: string): Promise<void> {
  if (userId === friendId || !(await areFriends(env, userId, friendId))) {
    throw new HttpError(403, "messages require friendship");
  }
}

async function pushMessage(env: Env, userId: string, message: FriendMessage): Promise<void> {
  const stub = env.USER_HUB.get(env.USER_HUB.idFromName(userId));
  await stub.fetch("https://user-hub.internal/notification", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ type: "message", message }),
  }).catch(() => undefined);
}

function parseKind(value: unknown): MessageKind {
  if (value === "text" || value === "song" || value === "queue" || value === "playlist") return value;
  throw new HttpError(400, "invalid message kind");
}

function parseLimit(value: string | null): number {
  if (value === null || value === "") return 50;
  if (!/^\d{1,3}$/.test(value)) throw new HttpError(400, "invalid limit");
  const limit = Number(value);
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_HISTORY_LIMIT) {
    throw new HttpError(400, `limit must be between 1 and ${MAX_HISTORY_LIMIT}`);
  }
  return limit;
}

function parseBefore(value: string | null): number | null {
  if (value === null || value === "") return null;
  if (!/^\d+$/.test(value)) throw new HttpError(400, "invalid before cursor");
  const id = Number(value);
  if (!Number.isSafeInteger(id) || id <= 0) throw new HttpError(400, "invalid before cursor");
  return id;
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
    if (bytes > MAX_REQUEST_BYTES) throw new HttpError(413, "message payload too large");
  }
  if (!request.body) throw new HttpError(400, "invalid json");

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    if (!value) continue;
    total += value.byteLength;
    if (total > MAX_REQUEST_BYTES) {
      await reader.cancel().catch(() => undefined);
      throw new HttpError(413, "message payload too large");
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
    throw new HttpError(400, "invalid json");
  }
}

function requireObject(value: unknown, label: string): JsonObject {
  if (!isRecord(value)) throw new HttpError(400, `${label} must be an object`);
  return value;
}

function requireArray(value: unknown, label: string, min: number, max: number): unknown[] {
  if (!Array.isArray(value) || value.length < min || value.length > max) {
    throw new HttpError(400, `${label} must contain between ${min} and ${max} items`);
  }
  return value;
}

function requireString(value: unknown, label: string, maxChars: number, trim: boolean): string {
  if (typeof value !== "string") throw new HttpError(400, `${label} must be a string`);
  const result = trim ? value.trim() : value;
  if (!result || result.length > maxChars) throw new HttpError(400, `invalid ${label}`);
  return result;
}

function optionalString(value: unknown, label: string, maxChars: number, trim: boolean): string | undefined {
  if (value === undefined || value === null || value === "") return undefined;
  return requireString(value, label, maxChars, trim);
}

function optionalHttpsUrl(value: unknown, label: string): string | undefined {
  const raw = optionalString(value, label, 2_048, true);
  if (raw === undefined) return undefined;
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new HttpError(400, `invalid ${label}`);
  }
  if (url.protocol !== "https:") throw new HttpError(400, `invalid ${label}`);
  return url.toString();
}

function utf8Length(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function isRecord(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function pair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}
