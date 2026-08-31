import { HttpError, json, readJson } from "./http";
import type { Env, Presence, SessionUser, SocialUser } from "./types";
import { getUser, userFromRow } from "./users";

interface UserRow {
  id: string;
  discord_id: string;
  discord_username: string;
  display_name: string | null;
  avatar_hash: string | null;
}

export async function listFriends(env: Env, user: SessionUser): Promise<Response> {
  const rows = await env.DB.prepare(
    `SELECT u.id, u.discord_id, u.discord_username, u.display_name, u.avatar_hash
     FROM friendships f
     JOIN users u ON u.id = CASE WHEN f.user_low = ? THEN f.user_high ELSE f.user_low END
     WHERE f.user_low = ? OR f.user_high = ?
     ORDER BY lower(COALESCE(u.display_name, u.discord_username)), lower(u.discord_username)`,
  ).bind(user.id, user.id, user.id).all<UserRow>();
  return json({ friends: rows.results.map(userFromRow) });
}

export async function listFriendPresence(env: Env, user: SessionUser): Promise<Response> {
  const ids = await friendIds(env, user.id);
  const entries = await Promise.all(
    ids.slice(0, 100).map(async (friendId) => {
      const stub = env.USER_HUB.get(env.USER_HUB.idFromName(friendId));
      const response = await stub.fetch("https://user-hub.internal/snapshot");
      const presence = response.ok
        ? await response.json<Presence>()
        : ({ online: false } satisfies Presence);
      return { userId: friendId, presence };
    }),
  );
  return json({ friends: entries });
}

export async function listRequests(env: Env, user: SessionUser): Promise<Response> {
  const incoming = await env.DB.prepare(
    `SELECT u.id, u.discord_id, u.discord_username, u.display_name, u.avatar_hash, r.created_at
     FROM friend_requests r JOIN users u ON u.id = r.sender_id
     WHERE r.receiver_id = ? ORDER BY r.created_at DESC LIMIT 100`,
  ).bind(user.id).all<UserRow & { created_at: number }>();
  const outgoing = await env.DB.prepare(
    `SELECT u.id, u.discord_id, u.discord_username, u.display_name, u.avatar_hash, r.created_at
     FROM friend_requests r JOIN users u ON u.id = r.receiver_id
     WHERE r.sender_id = ? ORDER BY r.created_at DESC LIMIT 100`,
  ).bind(user.id).all<UserRow & { created_at: number }>();
  return json({
    incoming: incoming.results.map((row) => ({ user: userFromRow(row), createdAt: row.created_at })),
    outgoing: outgoing.results.map((row) => ({ user: userFromRow(row), createdAt: row.created_at })),
  });
}

export async function searchUsers(request: Request, env: Env, user: SessionUser): Promise<Response> {
  const query = new URL(request.url).searchParams.get("q")?.trim() ?? "";
  if (query.length < 2 || query.length > 32) return json({ users: [] });
  const normalized = query.toLowerCase();
  const rows = await env.DB.prepare(
    `SELECT u.id, u.discord_id, u.discord_username, u.display_name, u.avatar_hash
     FROM users u
     WHERE u.id <> ?
       AND (lower(u.discord_username) = ? OR lower(COALESCE(u.display_name, '')) = ?)
       AND NOT EXISTS (
         SELECT 1 FROM blocks b
         WHERE (b.blocker_id = ? AND b.blocked_id = u.id)
            OR (b.blocker_id = u.id AND b.blocked_id = ?)
       )
     LIMIT 10`,
  ).bind(user.id, normalized, normalized, user.id, user.id).all<UserRow>();
  return json({ users: rows.results.map(userFromRow) });
}

export async function sendFriendRequest(env: Env, user: SessionUser, targetId: string): Promise<Response> {
  await requireAvailableTarget(env, user.id, targetId);
  if (await areFriends(env, user.id, targetId)) throw new HttpError(409, "already friends");
  const reverse = await env.DB.prepare(
    "SELECT 1 AS found FROM friend_requests WHERE sender_id = ? AND receiver_id = ?",
  ).bind(targetId, user.id).first<{ found: number }>();
  if (reverse) {
    await acceptFriendRequest(env, user, targetId);
    return json({ accepted: true });
  }
  await env.DB.prepare(
    "INSERT OR IGNORE INTO friend_requests(sender_id, receiver_id, created_at) VALUES (?, ?, ?)",
  ).bind(user.id, targetId, Date.now()).run();
  await notify(env, targetId, { type: "friend_request", from: publicUser(user) });
  return json({ sent: true }, 201);
}

export async function acceptFriendRequest(env: Env, user: SessionUser, senderId: string): Promise<Response> {
  await requireAvailableTarget(env, user.id, senderId);
  const pending = await env.DB.prepare(
    "SELECT 1 AS found FROM friend_requests WHERE sender_id = ? AND receiver_id = ?",
  ).bind(senderId, user.id).first<{ found: number }>();
  if (!pending) throw new HttpError(404, "friend request not found");
  const [low, high] = pair(user.id, senderId);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("INSERT OR IGNORE INTO friendships(user_low, user_high, created_at) VALUES (?, ?, ?)").bind(low, high, now),
    env.DB.prepare("DELETE FROM friend_requests WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)").bind(user.id, senderId, senderId, user.id),
  ]);
  const sender = await getUser(env, senderId);
  await syncPresencePair(env, user.id, senderId);
  await notify(env, senderId, { type: "friend_accepted", user: publicUser(user) });
  return json({ friend: sender });
}

export async function declineFriendRequest(env: Env, user: SessionUser, senderId: string): Promise<Response> {
  await env.DB.prepare("DELETE FROM friend_requests WHERE sender_id = ? AND receiver_id = ?")
    .bind(senderId, user.id).run();
  return new Response(null, { status: 204 });
}

export async function removeFriend(env: Env, user: SessionUser, targetId: string): Promise<Response> {
  const [low, high] = pair(user.id, targetId);
  await env.DB.prepare("DELETE FROM friendships WHERE user_low = ? AND user_high = ?").bind(low, high).run();
  await notify(env, targetId, { type: "friend_removed", userId: user.id });
  return new Response(null, { status: 204 });
}

export async function blockUser(env: Env, user: SessionUser, targetId: string): Promise<Response> {
  if (user.id === targetId) throw new HttpError(400, "cannot block yourself");
  const target = await getUser(env, targetId);
  if (!target) throw new HttpError(404, "user not found");
  const [low, high] = pair(user.id, targetId);
  await env.DB.batch([
    env.DB.prepare("INSERT OR IGNORE INTO blocks(blocker_id, blocked_id, created_at) VALUES (?, ?, ?)").bind(user.id, targetId, Date.now()),
    env.DB.prepare("DELETE FROM friendships WHERE user_low = ? AND user_high = ?").bind(low, high),
    env.DB.prepare("DELETE FROM friend_requests WHERE (sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)").bind(user.id, targetId, targetId, user.id),
  ]);
  await notify(env, targetId, { type: "friend_removed", userId: user.id });
  return new Response(null, { status: 204 });
}

export async function sendPartyInvite(
  request: Request,
  env: Env,
  user: SessionUser,
  targetId: string,
): Promise<Response> {
  if (!(await areFriends(env, user.id, targetId))) throw new HttpError(403, "party invites require friendship");
  const body = await readJson<{ partyUrl?: string }>(request);
  const partyUrl = validatePartyUrl(body.partyUrl ?? "");
  const stub = env.USER_HUB.get(env.USER_HUB.idFromName(targetId));
  const response = await stub.fetch("https://user-hub.internal/party-invite", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      sender: publicUser(user),
      partyUrl,
      sentAt: Date.now(),
    }),
  });
  if (response.status === 409) throw new HttpError(409, "friend is offline");
  if (!response.ok) throw new HttpError(502, "could not deliver party invite");
  return json({ delivered: true });
}

export async function areFriends(env: Env, a: string, b: string): Promise<boolean> {
  const [low, high] = pair(a, b);
  const row = await env.DB.prepare(
    "SELECT 1 AS found FROM friendships WHERE user_low = ? AND user_high = ?",
  ).bind(low, high).first<{ found: number }>();
  return !!row;
}

export async function friendIds(env: Env, userId: string): Promise<string[]> {
  const rows = await env.DB.prepare(
    `SELECT CASE WHEN user_low = ? THEN user_high ELSE user_low END AS friend_id
     FROM friendships WHERE user_low = ? OR user_high = ? LIMIT 250`,
  ).bind(userId, userId, userId).all<{ friend_id: string }>();
  return rows.results.map((row) => row.friend_id);
}

async function requireAvailableTarget(env: Env, sourceId: string, targetId: string): Promise<void> {
  if (sourceId === targetId) throw new HttpError(400, "cannot add yourself");
  const target = await getUser(env, targetId);
  if (!target) throw new HttpError(404, "user not found");
  const blocked = await env.DB.prepare(
    `SELECT 1 AS found FROM blocks
     WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
     LIMIT 1`,
  ).bind(sourceId, targetId, targetId, sourceId).first<{ found: number }>();
  if (blocked) throw new HttpError(404, "user not found");
}

async function notify(env: Env, userId: string, payload: unknown): Promise<void> {
  const stub = env.USER_HUB.get(env.USER_HUB.idFromName(userId));
  await stub.fetch("https://user-hub.internal/notification", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(payload),
  }).catch(() => undefined);
}

async function syncPresencePair(env: Env, firstUserId: string, secondUserId: string): Promise<void> {
  const firstHub = env.USER_HUB.get(env.USER_HUB.idFromName(firstUserId));
  const secondHub = env.USER_HUB.get(env.USER_HUB.idFromName(secondUserId));
  const [firstResponse, secondResponse] = await Promise.all([
    firstHub.fetch("https://user-hub.internal/snapshot"),
    secondHub.fetch("https://user-hub.internal/snapshot"),
  ]);
  const [firstPresence, secondPresence] = await Promise.all([
    firstResponse.ok ? firstResponse.json<Presence>() : Promise.resolve({ online: false } satisfies Presence),
    secondResponse.ok ? secondResponse.json<Presence>() : Promise.resolve({ online: false } satisfies Presence),
  ]);
  await Promise.all([
    firstHub.fetch("https://user-hub.internal/friend-presence", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userId: secondUserId, presence: secondPresence }),
    }).catch(() => undefined),
    secondHub.fetch("https://user-hub.internal/friend-presence", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userId: firstUserId, presence: firstPresence }),
    }).catch(() => undefined),
  ]);
}

function pair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}

function publicUser(user: SessionUser): SocialUser {
  const { sessionHash: _sessionHash, ...publicValue } = user;
  return publicValue;
}

function validatePartyUrl(value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new HttpError(400, "invalid party invite");
  }
  if (
    url.protocol !== "https:" ||
    !url.hostname.toLowerCase().endsWith(".trycloudflare.com") ||
    url.pathname !== "/party" ||
    !url.searchParams.get("token")
  ) {
    throw new HttpError(400, "invalid ArchiveTune party invite");
  }
  return url.toString();
}
