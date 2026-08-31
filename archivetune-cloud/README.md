# ArchiveTune social backend

Serverless social control plane for ArchiveTune desktop:

- Discord OAuth identity (identify only)
- Cloudflare D1 for users, sessions, friend requests, friendships, blocks and friend-only statistics snapshots
- a hibernating Durable Object UserHub per user for presence and live notifications
- live-only delivery of the existing ArchiveTune Quick Tunnel party invite

The backend never receives YouTube cookies, playback URLs, downloads, stems, analysis data or audio. Shared listening-statistics snapshots are authenticated, size-limited, reduced to the fields needed by the Statistics UI, and readable only by current friends.

## Discord application

Create one application in the Discord Developer Portal and add this OAuth2 redirect:

    https://archivetune-social.archivetune.workers.dev/auth/discord/callback

No bot is required. ArchiveTune requests only the identify scope.

Then set the two remaining Worker secrets locally:

    cd C:\Users\mint\Documents\ArchiveTunePC\archivetune-cloud
    npx wrangler secret put DISCORD_CLIENT_ID
    npx wrangler secret put DISCORD_CLIENT_SECRET

Do not commit either value.

## Development

Install dependencies and type-check:

    npm install
    npm run check

For local development, copy .dev.vars.example to .dev.vars and fill the values. .dev.vars is ignored by git.

## Database

Remote D1 migrations:

    npx wrangler d1 migrations apply archivetune-social --remote

## Deploy

    npm run check
    npx wrangler deploy

Production Worker:

    https://archivetune-social.archivetune.workers.dev

Health check:

    GET /health
