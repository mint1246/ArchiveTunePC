import { authenticate, discordCallback, discordStart, exchangeAuthCode, revokeSession } from "./auth";
import { error, HttpError, json, requireMethod } from "./http";
import {
  acceptFriendRequest,
  blockUser,
  declineFriendRequest,
  listFriends,
  listFriendPresence,
  listRequests,
  removeFriend,
  searchUsers,
  sendFriendRequest,
  sendPartyInvite,
} from "./social";
import type { Env } from "./types";
import { UserHub } from "./userHub";
import { getFriendStatistics, putStatistics } from "./statistics";
import { getFriendMessages, sendFriendMessage } from "./messages";

export { UserHub };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      const url = new URL(request.url);
      if (request.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: corsHeaders(request) });
      }
      if (url.pathname === "/health") return json({ ok: true, service: "archivetune-social" });
      if (url.pathname === "/auth/discord/start") {
        requireMethod(request, "GET");
        return await discordStart(request, env);
      }
      if (url.pathname === "/auth/discord/callback") {
        requireMethod(request, "GET");
        return await discordCallback(request, env);
      }
      if (url.pathname === "/v1/auth/exchange") {
        requireMethod(request, "POST");
        return await exchangeAuthCode(request, env);
      }

      const user = await authenticate(request, env);
      if (url.pathname === "/v1/me") {
        requireMethod(request, "GET");
        const { sessionHash: _sessionHash, ...publicUser } = user;
        return json({ user: publicUser });
      }
      if (url.pathname === "/v1/auth/logout") {
        requireMethod(request, "POST");
        await revokeSession(request, env);
        return new Response(null, { status: 204 });
      }
      if (url.pathname === "/v1/friends") {
        requireMethod(request, "GET");
        return await listFriends(env, user);
      }
      if (url.pathname === "/v1/friends/presence") {
        requireMethod(request, "GET");
        return await listFriendPresence(env, user);
      }
      if (url.pathname === "/v1/friend-requests") {
        requireMethod(request, "GET");
        return await listRequests(env, user);
      }
      if (url.pathname === "/v1/users/search") {
        requireMethod(request, "GET");
        return await searchUsers(request, env, user);
      }
      if (url.pathname === "/v1/statistics") {
        requireMethod(request, "PUT");
        return await putStatistics(request, env, user);
      }
      if (url.pathname === "/v1/live") {
        requireMethod(request, "GET");
        if ((request.headers.get("upgrade") ?? "").toLowerCase() !== "websocket") {
          throw new HttpError(426, "websocket required");
        }
        const deviceId = url.searchParams.get("device")?.trim() ?? "";
        if (!/^[A-Za-z0-9._-]{1,128}$/.test(deviceId)) throw new HttpError(400, "invalid device id");
        const stub = env.USER_HUB.get(env.USER_HUB.idFromName(user.id));
        const headers = new Headers(request.headers);
        headers.set("x-archivetune-user-id", user.id);
        headers.set("x-archivetune-device-id", deviceId);
        headers.delete("authorization");
        return await stub.fetch(new Request("https://user-hub.internal/connect", { method: "GET", headers }));
      }

      const friendRequestMatch = url.pathname.match(/^\/v1\/friend-requests\/([^/]+)$/);
      if (friendRequestMatch) {
        const targetId = decodeURIComponent(friendRequestMatch[1]);
        if (request.method === "POST") return await sendFriendRequest(env, user, targetId);
        if (request.method === "DELETE") return await declineFriendRequest(env, user, targetId);
        throw new HttpError(405, "method not allowed");
      }
      const acceptMatch = url.pathname.match(/^\/v1\/friend-requests\/([^/]+)\/accept$/);
      if (acceptMatch) {
        requireMethod(request, "POST");
        return await acceptFriendRequest(env, user, decodeURIComponent(acceptMatch[1]));
      }
      const friendMatch = url.pathname.match(/^\/v1\/friends\/([^/]+)$/);
      if (friendMatch) {
        requireMethod(request, "DELETE");
        return await removeFriend(env, user, decodeURIComponent(friendMatch[1]));
      }
      const friendStatisticsMatch = url.pathname.match(/^\/v1\/friends\/([^/]+)\/statistics$/);
      if (friendStatisticsMatch) {
        requireMethod(request, "GET");
        return await getFriendStatistics(env, user, decodeURIComponent(friendStatisticsMatch[1]));
      }
      const friendMessagesMatch = url.pathname.match(/^\/v1\/friends\/([^/]+)\/messages$/);
      if (friendMessagesMatch) {
        const friendId = decodeURIComponent(friendMessagesMatch[1]);
        if (request.method === "GET") return await getFriendMessages(request, env, user, friendId);
        if (request.method === "POST") return await sendFriendMessage(request, env, user, friendId);
        throw new HttpError(405, "method not allowed");
      }
      const blockMatch = url.pathname.match(/^\/v1\/blocks\/([^/]+)$/);
      if (blockMatch) {
        requireMethod(request, "POST");
        return await blockUser(env, user, decodeURIComponent(blockMatch[1]));
      }
      const partyMatch = url.pathname.match(/^\/v1\/party-invites\/([^/]+)$/);
      if (partyMatch) {
        requireMethod(request, "POST");
        return await sendPartyInvite(request, env, user, decodeURIComponent(partyMatch[1]));
      }
      return error("not found", 404);
    } catch (cause) {
      if (cause instanceof HttpError) return error(cause.message, cause.status);
      console.error("request failed", cause instanceof Error ? cause.message : String(cause));
      return error("internal server error", 500);
    }
  },
} satisfies ExportedHandler<Env>;

function corsHeaders(request: Request): HeadersInit {
  // Desktop clients do not need CORS. This only permits same-origin web tooling.
  const origin = new URL(request.url).origin;
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-headers": "authorization, content-type",
    "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
  };
}
