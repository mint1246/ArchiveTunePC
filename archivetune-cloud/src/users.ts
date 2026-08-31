import type { Env, SocialUser } from "./types";

interface UserRow {
  id: string;
  discord_id: string;
  discord_username: string;
  display_name: string | null;
  avatar_hash: string | null;
}

export function userFromRow(row: UserRow): SocialUser {
  return {
    id: row.id,
    discordId: row.discord_id,
    username: row.discord_username,
    displayName: row.display_name,
    avatarUrl: avatarUrl(row.discord_id, row.avatar_hash),
  };
}

export async function getUser(env: Env, id: string): Promise<SocialUser | null> {
  const row = await env.DB.prepare(
    "SELECT id, discord_id, discord_username, display_name, avatar_hash FROM users WHERE id = ?",
  ).bind(id).first<UserRow>();
  return row ? userFromRow(row) : null;
}

export function avatarUrl(discordId: string, avatarHash: string | null): string | null {
  if (!avatarHash) return null;
  const extension = avatarHash.startsWith("a_") ? "gif" : "png";
  return `https://cdn.discordapp.com/avatars/${discordId}/${avatarHash}.${extension}?size=128`;
}
