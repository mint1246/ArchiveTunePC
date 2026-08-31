export interface Env {
  DB: D1Database;
  USER_HUB: DurableObjectNamespace;
  DISCORD_CLIENT_ID: string;
  DISCORD_CLIENT_SECRET: string;
  OAUTH_STATE_SECRET: string;
  SESSION_PEPPER: string;
}

export interface SocialUser {
  id: string;
  discordId: string;
  username: string;
  displayName: string | null;
  avatarUrl: string | null;
}

export interface SessionUser extends SocialUser {
  sessionHash: string;
}

export interface Presence {
  online: boolean;
  songId?: string;
  title?: string;
  artist?: string;
  artworkUrl?: string;
  partyJoinable?: boolean;
  updatedAt?: number;
}

export interface SocketAttachment {
  userId: string;
  deviceId: string;
  connectedAt: number;
  presence: Presence;
}
