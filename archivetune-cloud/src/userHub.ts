import { DurableObject } from "cloudflare:workers";
import type { Env, Presence, SocketAttachment } from "./types";
import { friendIds } from "./social";

export class UserHub extends DurableObject<Env> {
  private userId: string | null = null;

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/connect") return this.connectWebSocket(request);
    if (url.pathname === "/snapshot") return this.snapshotResponse();
    if (url.pathname === "/friend-presence") return this.receiveFriendPresence(request);
    if (url.pathname === "/notification") return this.broadcastJson(await request.json());
    if (url.pathname === "/party-invite") return this.partyInvite(request);
    return new Response("not found", { status: 404 });
  }

  async webSocketMessage(webSocket: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (typeof message !== "string") return;
    let value: unknown;
    try {
      value = JSON.parse(message);
    } catch {
      return;
    }
    if (!isRecord(value)) return;
    const type = value.type;
    if (type === "ping") {
      webSocket.send(JSON.stringify({ type: "pong", at: Date.now() }));
      return;
    }
    if (type !== "presence.update") return;

    const attachment = webSocket.deserializeAttachment() as SocketAttachment | null;
    if (!attachment) return;
    const presence = sanitizePresence(value.presence);
    attachment.presence = { ...presence, online: true, updatedAt: Date.now() };
    webSocket.serializeAttachment(attachment);
    await this.publishPresence();
  }

  async webSocketClose(
    webSocket: WebSocket,
    _code: number,
    _reason: string,
    _wasClean: boolean,
  ): Promise<void> {
    webSocket.close();
    await this.publishPresence();
  }

  async webSocketError(webSocket: WebSocket): Promise<void> {
    webSocket.close(1011, "websocket error");
    await this.publishPresence();
  }

  private async connectWebSocket(request: Request): Promise<Response> {
    const userId = request.headers.get("x-archivetune-user-id") ?? "";
    const deviceId = request.headers.get("x-archivetune-device-id") ?? "";
    if (!userId || !deviceId) return new Response("bad request", { status: 400 });
    if (this.userId && this.userId !== userId) return new Response("user mismatch", { status: 409 });
    this.userId = userId;

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    const attachment: SocketAttachment = {
      userId,
      deviceId: deviceId.slice(0, 128),
      connectedAt: Date.now(),
      presence: { online: true, updatedAt: Date.now() },
    };
    server.serializeAttachment(attachment);
    server.send(JSON.stringify({ type: "ready", userId }));

    await this.sendInitialFriendPresence(server, userId);
    await this.publishPresence();
    return new Response(null, { status: 101, webSocket: client });
  }

  private async sendInitialFriendPresence(socket: WebSocket, userId: string): Promise<void> {
    const ids = await friendIds(this.env, userId);
    const snapshots = await Promise.all(
      ids.slice(0, 100).map(async (friendId) => {
        const stub = this.env.USER_HUB.get(this.env.USER_HUB.idFromName(friendId));
        const response = await stub.fetch("https://user-hub.internal/snapshot");
        if (!response.ok) return { userId: friendId, presence: { online: false } as Presence };
        return { userId: friendId, presence: await response.json<Presence>() };
      }),
    );
    socket.send(JSON.stringify({ type: "presence.initial", friends: snapshots }));
  }

  private snapshotResponse(): Response {
    return Response.json(this.aggregatePresence());
  }

  private async receiveFriendPresence(request: Request): Promise<Response> {
    const value = await request.json<{ userId?: string; presence?: Presence }>();
    if (!value.userId || !value.presence) return new Response("bad request", { status: 400 });
    return this.broadcastJson({
      type: "friend_presence",
      userId: value.userId,
      presence: value.presence,
    });
  }

  private async partyInvite(request: Request): Promise<Response> {
    if (this.ctx.getWebSockets().length === 0) return new Response("offline", { status: 409 });
    const value = await request.json();
    return this.broadcastJson({ type: "party_invite", ...asObject(value) });
  }

  private async publishPresence(): Promise<void> {
    const userId = this.resolveUserId();
    if (!userId) return;
    const presence = this.aggregatePresence();
    const ids = await friendIds(this.env, userId);
    await Promise.all(
      ids.map(async (friendId) => {
        const stub = this.env.USER_HUB.get(this.env.USER_HUB.idFromName(friendId));
        await stub.fetch("https://user-hub.internal/friend-presence", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ userId, presence }),
        }).catch(() => undefined);
      }),
    );
  }

  private resolveUserId(): string | null {
    if (this.userId) return this.userId;
    for (const socket of this.ctx.getWebSockets()) {
      const attachment = socket.deserializeAttachment() as SocketAttachment | null;
      if (attachment?.userId) {
        this.userId = attachment.userId;
        return this.userId;
      }
    }
    return null;
  }

  private aggregatePresence(): Presence {
    const attachments = this.ctx.getWebSockets()
      .map((socket) => socket.deserializeAttachment() as SocketAttachment | null)
      .filter((value): value is SocketAttachment => !!value);
    if (attachments.length === 0) return { online: false, updatedAt: Date.now() };
    const latest = attachments.reduce((best, current) =>
      (current.presence.updatedAt ?? current.connectedAt) > (best.presence.updatedAt ?? best.connectedAt)
        ? current
        : best,
    );
    return { ...latest.presence, online: true };
  }

  private broadcastJson(payload: unknown): Response {
    const sockets = this.ctx.getWebSockets();
    const encoded = JSON.stringify(payload);
    for (const socket of sockets) {
      try {
        socket.send(encoded);
      } catch {
        // A close/error callback will eventually prune this socket.
      }
    }
    return Response.json({ delivered: sockets.length });
  }
}

function sanitizePresence(value: unknown): Presence {
  if (!isRecord(value)) return { online: true };
  const result: Presence = { online: true };
  if (typeof value.songId === "string") result.songId = value.songId.slice(0, 256);
  if (typeof value.title === "string") result.title = value.title.slice(0, 256);
  if (typeof value.artist === "string") result.artist = value.artist.slice(0, 256);
  if (typeof value.artworkUrl === "string" && value.artworkUrl.startsWith("https://")) {
    result.artworkUrl = value.artworkUrl.slice(0, 2048);
  }
  if (typeof value.partyJoinable === "boolean") result.partyJoinable = value.partyJoinable;
  return result;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asObject(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}
