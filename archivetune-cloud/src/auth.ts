import { hmac, randomToken, secureEqual, sha256 } from "./crypto";
import { HttpError } from "./http";
import type { Env, SessionUser } from "./types";
import { userFromRow } from "./users";

const OAUTH_STATE_TTL_MS = 10 * 60_000;
const AUTH_CODE_TTL_MS = 5 * 60_000;
const SESSION_TTL_MS = 90 * 24 * 60 * 60_000;

interface OAuthStatePayload {
  nonce: string;
  port: number;
  issuedAt: number;
  token: string;
}

interface DiscordUser {
  id: string;
  username: string;
  global_name?: string | null;
  avatar?: string | null;
}

interface UserRow {
  id: string;
  discord_id: string;
  discord_username: string;
  display_name: string | null;
  avatar_hash: string | null;
}

export async function discordStart(request: Request, env: Env): Promise<Response> {
  requireDiscordConfig(env);
  const url = new URL(request.url);
  const nonce = url.searchParams.get("nonce")?.trim() ?? "";
  const port = Number(url.searchParams.get("port"));
  if (!/^[A-Za-z0-9_-]{20,160}$/.test(nonce)) {
    throw new HttpError(400, "invalid login nonce");
  }
  if (!Number.isInteger(port) || port < 1024 || port > 65535) {
    throw new HttpError(400, "invalid loopback port");
  }

  const payload: OAuthStatePayload = {
    nonce,
    port,
    issuedAt: Date.now(),
    token: randomToken(18),
  };
  const encoded = base64UrlJson(payload);
  const signature = await hmac(env.OAUTH_STATE_SECRET, encoded);
  const state = `${encoded}.${signature}`;
  const callback = `${url.origin}/auth/discord/callback`;

  const authorize = new URL("https://discord.com/oauth2/authorize");
  authorize.searchParams.set("response_type", "code");
  authorize.searchParams.set("client_id", env.DISCORD_CLIENT_ID);
  authorize.searchParams.set("scope", "identify");
  authorize.searchParams.set("redirect_uri", callback);
  authorize.searchParams.set("state", state);
  authorize.searchParams.set("prompt", "consent");
  return Response.redirect(authorize.toString(), 302);
}

export async function discordCallback(request: Request, env: Env): Promise<Response> {
  requireDiscordConfig(env);
  const url = new URL(request.url);
  const state = url.searchParams.get("state") ?? "";
  const payload = await verifyState(state, env.OAUTH_STATE_SECRET);
  const error = url.searchParams.get("error");
  if (error) return loopbackRedirect(payload, { error: "discord authorization was cancelled" });

  const code = url.searchParams.get("code") ?? "";
  const callback = `${url.origin}/auth/discord/callback`;

  const body = new URLSearchParams({
    client_id: env.DISCORD_CLIENT_ID,
    client_secret: env.DISCORD_CLIENT_SECRET,
    grant_type: "authorization_code",
    code,
    redirect_uri: callback,
  });
  const tokenResponse = await fetch("https://discord.com/api/v10/oauth2/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  if (!tokenResponse.ok) throw new HttpError(502, "discord token exchange failed");
  const tokenJson = await tokenResponse.json<{ access_token?: string }>();
  if (!tokenJson.access_token) throw new HttpError(502, "discord token response was incomplete");

  const userResponse = await fetch("https://discord.com/api/v10/users/@me", {
    headers: { authorization: `Bearer ${tokenJson.access_token}` },
  });
  if (!userResponse.ok) throw new HttpError(502, "discord user lookup failed");
  const discord = await userResponse.json<DiscordUser>();
  if (!discord.id || !discord.username) throw new HttpError(502, "discord user response was incomplete");

  const now = Date.now();
  const existing = await env.DB.prepare("SELECT id FROM users WHERE discord_id = ?")
    .bind(discord.id)
    .first<{ id: string }>();
  const userId = existing?.id ?? `usr_${randomToken(18)}`;
  await env.DB.prepare(
    `INSERT INTO users(id, discord_id, discord_username, display_name, avatar_hash, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(discord_id) DO UPDATE SET
       discord_username = excluded.discord_username,
       display_name = excluded.display_name,
       avatar_hash = excluded.avatar_hash,
       updated_at = excluded.updated_at`,
  ).bind(
    userId,
    discord.id,
    discord.username,
    discord.global_name ?? null,
    discord.avatar ?? null,
    now,
    now,
  ).run();

  const authCode = randomToken(32);
  await env.DB.prepare(
    "INSERT INTO auth_codes(code_hash, nonce_hash, user_id, created_at, expires_at, consumed_at) VALUES (?, ?, ?, ?, ?, NULL)",
  ).bind(
    await sha256(authCode),
    await sha256(payload.nonce),
    userId,
    now,
    now + AUTH_CODE_TTL_MS,
  ).run();

  return loopbackRedirect(payload, { code: authCode });
}

export async function exchangeAuthCode(request: Request, env: Env): Promise<Response> {
  const payload = await request.json<{ code?: string; nonce?: string }>();
  const code = payload.code?.trim() ?? "";
  const nonce = payload.nonce?.trim() ?? "";
  if (!code || !nonce) throw new HttpError(400, "missing login code");

  const now = Date.now();
  const claimed = await env.DB.prepare(
    `UPDATE auth_codes
     SET consumed_at = ?
     WHERE code_hash = ? AND nonce_hash = ? AND consumed_at IS NULL AND expires_at > ?
     RETURNING user_id`,
  ).bind(now, await sha256(code), await sha256(nonce), now).first<{ user_id: string }>();
  if (!claimed) throw new HttpError(401, "login code is invalid or expired");

  const token = `ats_${randomToken(32)}`;
  const tokenHash = await sessionHash(token, env);
  await env.DB.prepare(
    "INSERT INTO sessions(token_hash, user_id, created_at, expires_at, last_used_at) VALUES (?, ?, ?, ?, ?)",
  ).bind(tokenHash, claimed.user_id, now, now + SESSION_TTL_MS, now).run();
  return new Response(JSON.stringify({ token, expiresAt: now + SESSION_TTL_MS }), {
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

export async function authenticate(request: Request, env: Env): Promise<SessionUser> {
  const header = request.headers.get("authorization") ?? "";
  if (!header.toLowerCase().startsWith("bearer ")) throw new HttpError(401, "authentication required");
  const token = header.slice(7).trim();
  if (!token.startsWith("ats_") || token.length < 40) throw new HttpError(401, "invalid session");
  const hash = await sessionHash(token, env);
  const now = Date.now();
  const row = await env.DB.prepare(
    `SELECT u.id, u.discord_id, u.discord_username, u.display_name, u.avatar_hash
     FROM sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > ?`,
  ).bind(hash, now).first<UserRow>();
  if (!row) throw new HttpError(401, "session expired");

  // This write is deliberately coarse to avoid turning every API call into a D1 write.
  const lastTouched = await env.DB.prepare("SELECT last_used_at FROM sessions WHERE token_hash = ?")
    .bind(hash).first<{ last_used_at: number }>();
  if (!lastTouched || now - lastTouched.last_used_at > 6 * 60 * 60_000) {
    await env.DB.prepare("UPDATE sessions SET last_used_at = ? WHERE token_hash = ?").bind(now, hash).run();
  }
  return { ...userFromRow(row), sessionHash: hash };
}

export async function revokeSession(request: Request, env: Env): Promise<void> {
  const user = await authenticate(request, env);
  await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(user.sessionHash).run();
}

async function verifyState(value: string, secret: string): Promise<OAuthStatePayload> {
  const split = value.lastIndexOf(".");
  if (split <= 0) throw new HttpError(400, "invalid oauth state");
  const encoded = value.slice(0, split);
  const supplied = value.slice(split + 1);
  const expected = await hmac(secret, encoded);
  if (!(await secureEqual(supplied, expected))) throw new HttpError(400, "invalid oauth state");
  let payload: OAuthStatePayload;
  try {
    payload = JSON.parse(new TextDecoder().decode(base64UrlDecode(encoded))) as OAuthStatePayload;
  } catch {
    throw new HttpError(400, "invalid oauth state");
  }
  if (
    !payload.nonce ||
    !payload.token ||
    !Number.isInteger(payload.port) ||
    Date.now() - payload.issuedAt < 0 ||
    Date.now() - payload.issuedAt > OAUTH_STATE_TTL_MS
  ) {
    throw new HttpError(400, "oauth state expired");
  }
  return payload;
}

async function sessionHash(token: string, env: Env): Promise<string> {
  return sha256(`${env.SESSION_PEPPER}\u0000${token}`);
}

function requireDiscordConfig(env: Env): void {
  if (!env.DISCORD_CLIENT_ID || !env.DISCORD_CLIENT_SECRET) {
    throw new HttpError(503, "discord oauth is not configured");
  }
  if (!env.OAUTH_STATE_SECRET || !env.SESSION_PEPPER) {
    throw new HttpError(503, "server auth secrets are not configured");
  }
}

function loopbackRedirect(payload: OAuthStatePayload, values: { code?: string; error?: string }): Response {
  const redirect = new URL(`http://127.0.0.1:${payload.port}/archivetune-auth`);
  redirect.searchParams.set("nonce", payload.nonce);
  if (values.code) redirect.searchParams.set("code", values.code);
  if (values.error) redirect.searchParams.set("error", values.error);
  return Response.redirect(redirect.toString(), 302);
}

function base64UrlJson(value: unknown): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlDecode(value: string): Uint8Array {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (value.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
