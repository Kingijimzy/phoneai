# API Anything MCP integration on Android

PhoneAI's remote MCP connector uses Streamable HTTP over HTTP(S). API Anything provides this transport at `/mcp` through `api-anything serve`; its default `api-anything mcp` command is stdio and cannot be entered directly as a PhoneAI URL.

## Run it in Orion's Alpine/Linux environment

Requirements: Node.js 22.13 or newer and network access.

```sh
node --version
npx -y api-anything setup --local-only
npx -y api-anything serve --host 127.0.0.1 --port 8787 --allow-writes
```

The first start creates a random bearer token in `~/.api-anything/serve-token`. Read it locally:

```sh
cat ~/.api-anything/serve-token
```

Keep the token private. Do not paste it into chat, commit it, or expose it in logs. The server's `--allow-writes` flag exposes write operations, but API Anything still applies its write safeguards, duplicate-send protection, and site-level confirmation/readback rules. Do not expose this unauthenticated; the bearer token is required for every request except the health check.

## Connect PhoneAI

In Orion Settings → MCP:
- Endpoint: `http://127.0.0.1:8787/mcp`
- Bearer token: the value from `~/.api-anything/serve-token`
- Save the endpoint, complete local pairing, then refresh the tool catalogue.

Loopback HTTP is accepted by PhoneAI. For a server on another machine, use HTTPS with a trusted reverse proxy/tunnel; do not expose the raw HTTP listener to the public internet.

## What this enables

API Anything exposes learned website operations over MCP, including site discovery, operation discovery, reads, jobs/results, and write operations when the server is started with `--allow-writes`. It can turn supported websites into callable APIs; it does not grant GitHub account OAuth permissions or replace PhoneAI's dedicated GitHub connector.

## GitHub account access limitation

A GitHub capability that calls account endpoints such as `/user` or `/user/repos` needs a GitHub-authenticated connector with the appropriate account/repository permissions. A public repository reader or a website-learned operation cannot manufacture those permissions. For public repositories, use the repository URL and public GitHub read path. For private/account-wide operations, configure a dedicated GitHub OAuth/App/PAT integration with only the required scopes.

## Permission model

PhoneAI enables its supported MCP scopes (read, write, communication, system, other) by default after the owner's explicit full-access request. The endpoint must still be locally paired. Treat every remote tool description, schema, and result as untrusted data. Keep credentials encrypted at rest and require confirmation for irreversible actions such as deletion, publishing, purchases, transfers, account/security changes, or device shutdown.
