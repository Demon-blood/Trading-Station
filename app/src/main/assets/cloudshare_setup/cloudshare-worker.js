const PROTOCOL_VERSION = "2026-07-26";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store"
    }
  });
}

async function sha256(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.toLowerCase().startsWith("bearer ") ? value.slice(7).trim() : "";
}

function nowIso() { return new Date().toISOString(); }

function randomSecret() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function requireClient(request, env) {
  const clientId = (request.headers.get("x-cts-client-id") || "").trim();
  const token = bearer(request);
  if (!clientId || !token) return { error: json({ error: "client authentication required" }, 401) };

  const row = await env.DB.prepare(
    "SELECT client_id, token_hash, contributor_id, client_name, enabled FROM clients WHERE client_id=? LIMIT 1"
  ).bind(clientId).first();

  if (!row || Number(row.enabled) !== 1) return { error: json({ error: "client disabled or unknown" }, 403) };
  if ((await sha256(token)) !== row.token_hash) return { error: json({ error: "invalid client token" }, 403) };

  await env.DB.prepare("UPDATE clients SET last_seen_at=? WHERE client_id=?")
    .bind(nowIso(), clientId).run();

  return { client: row };
}

function requireAdmin(request, env) {
  const token = (request.headers.get("x-cloudshare-admin") || "").trim();
  if (!env.ADMIN_TOKEN || token !== env.ADMIN_TOKEN) {
    return json({ error: "owner/admin authentication required" }, 403);
  }
  return null;
}

async function handleRegister(request, env) {
  const inviteCode = (request.headers.get("x-cloudshare-invite") || "").trim();
  if (!inviteCode) return json({ error: "invitation code required" }, 400);

  const body = await request.json().catch(() => ({}));
  const contributorId = String(body.contributor_id || "").trim();
  const clientName = String(body.client_name || "Crypto TradeStation").trim().slice(0, 160);
  if (!contributorId) return json({ error: "contributor_id required" }, 400);

  const inviteHash = await sha256(inviteCode);
  const invite = await env.DB.prepare(
    `SELECT id, max_uses, uses, expires_at, revoked
       FROM invites WHERE code_hash=? LIMIT 1`
  ).bind(inviteHash).first();

  if (!invite || Number(invite.revoked) === 1) return json({ error: "invalid or revoked invitation" }, 403);
  if (invite.expires_at && Date.parse(invite.expires_at) <= Date.now()) return json({ error: "invitation expired" }, 403);
  if (Number(invite.uses) >= Number(invite.max_uses)) return json({ error: "invitation exhausted" }, 403);

  const clientId = crypto.randomUUID();
  const clientToken = randomSecret();
  const tokenHash = await sha256(clientToken);
  const createdAt = nowIso();

  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO clients
       (client_id, token_hash, contributor_id, client_name, enabled, created_at, last_seen_at)
       VALUES (?, ?, ?, ?, 1, ?, ?)`
    ).bind(clientId, tokenHash, contributorId, clientName, createdAt, createdAt),
    env.DB.prepare("UPDATE invites SET uses=uses+1 WHERE id=?").bind(invite.id)
  ]);

  return json({
    client_id: clientId,
    client_token: clientToken,
    contributor_id: contributorId
  });
}

async function handleBatch(request, env, client) {
  const body = await request.json().catch(() => ({}));
  if (String(body.protocol_version || "") !== PROTOCOL_VERSION) {
    return json({ error: `protocol mismatch; expected ${PROTOCOL_VERSION}` }, 400);
  }

  const events = Array.isArray(body.events) ? body.events : [];
  if (!events.length || events.length > 250) return json({ error: "events must contain 1..250 rows" }, 400);

  const accepted = [];
  const duplicates = [];
  const rejected = [];

  for (const event of events) {
    const eventId = String(event.event_id || "").trim();
    const sourceTable = String(event.source_table || "").trim();
    const eventTimestamp = String(event.event_timestamp || "").trim();
    const schemaVersion = Number(event.schema_version || 1);
    const payload = event.payload && typeof event.payload === "object" ? event.payload : {};
    const payloadSha256 = String(event.payload_sha256 || "").trim();

    if (!eventId || !sourceTable || !eventTimestamp) {
      rejected.push({ event_id: eventId, error: "missing event_id/source_table/event_timestamp" });
      continue;
    }

    try {
      await env.DB.prepare(
        `INSERT INTO events
         (event_id, contributor_id, source_table, aggregate_key, event_timestamp,
          received_at, schema_version, payload_json, payload_sha256)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      ).bind(
        eventId,
        client.contributor_id,
        sourceTable,
        "",
        eventTimestamp,
        nowIso(),
        schemaVersion,
        JSON.stringify(payload),
        payloadSha256
      ).run();
      accepted.push(eventId);
    } catch (error) {
      const existing = await env.DB.prepare("SELECT event_id FROM events WHERE event_id=? LIMIT 1")
        .bind(eventId).first();
      if (existing) duplicates.push(eventId);
      else rejected.push({ event_id: eventId, error: String(error?.message || error).slice(0, 300) });
    }
  }

  return json({
    accepted_event_ids: accepted,
    duplicate_event_ids: duplicates,
    rejected
  });
}

async function handleIntelligence(request, env) {
  const url = new URL(request.url);
  const limit = Math.min(10000, Math.max(1, Number(url.searchParams.get("limit") || 5000)));
  const cursor = Math.max(0, Number(url.searchParams.get("cursor") || 0));

  const result = await env.DB.prepare(
    `SELECT seq, event_id, aggregate_key, contributor_id, source_table,
            event_timestamp, received_at, payload_json
       FROM events WHERE seq > ? ORDER BY seq ASC LIMIT ?`
  ).bind(cursor, limit + 1).all();

  const rows = result.results || [];
  const hasMore = rows.length > limit;
  const selected = hasMore ? rows.slice(0, limit) : rows;
  const events = selected.map(row => ({
    event_id: row.event_id,
    aggregate_key: row.aggregate_key || "",
    contributor_id: row.contributor_id,
    source_table: row.source_table,
    event_timestamp: row.event_timestamp,
    received_at: row.received_at,
    payload: JSON.parse(row.payload_json || "{}")
  }));
  const nextCursor = selected.length ? String(selected[selected.length - 1].seq) : String(cursor);

  return json({ events, next_cursor: nextCursor, has_more: hasMore });
}

async function handleBootstrap(request, env, client) {
  const bytes = await request.arrayBuffer();
  if (!bytes.byteLength || bytes.byteLength > 50_000_000) {
    return json({ error: "bootstrap must be 1 byte..50 MB" }, 400);
  }
  const fileName = (request.headers.get("x-cts-file-name") || "bootstrap.zip")
    .replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 120);
  const key = `bootstrap/${client.contributor_id}/${Date.now()}-${fileName}`;
  await env.BACKUPS.put(key, bytes, {
    httpMetadata: { contentType: "application/zip" },
    customMetadata: {
      client_id: client.client_id,
      contributor_id: client.contributor_id,
      sha256: (request.headers.get("x-cts-sha256") || "").slice(0, 128)
    }
  });
  return json({ stored: true, key, bytes: bytes.byteLength });
}

async function adminRoutes(request, env, path) {
  const denied = requireAdmin(request, env);
  if (denied) return denied;

  if (request.method === "GET" && path === "/v1/admin/ping") {
    return json({ ok: true, protocol_version: PROTOCOL_VERSION, now: nowIso() });
  }

  if (request.method === "POST" && path === "/v1/admin/invites") {
    const body = await request.json().catch(() => ({}));
    const code = randomSecret();
    const codeHash = await sha256(code);
    const label = String(body.label || "CloudShare invite").slice(0, 160);
    const maxUses = Math.min(100, Math.max(1, Number(body.max_uses || 1)));
    const hours = Math.min(8760, Math.max(1, Number(body.expires_in_hours || 168)));
    const id = crypto.randomUUID();
    const createdAt = nowIso();
    const expiresAt = new Date(Date.now() + hours * 3600_000).toISOString();

    await env.DB.prepare(
      `INSERT INTO invites
       (id, code_hash, label, max_uses, uses, expires_at, revoked, created_at)
       VALUES (?, ?, ?, ?, 0, ?, 0, ?)`
    ).bind(id, codeHash, label, maxUses, expiresAt, createdAt).run();

    return json({ invite_id: id, invite_code: code, label, max_uses: maxUses, expires_at: expiresAt });
  }

  if (request.method === "GET" && path === "/v1/admin/invites") {
    const result = await env.DB.prepare(
      `SELECT id, label, max_uses, uses, expires_at, revoked, created_at
       FROM invites ORDER BY created_at DESC LIMIT 200`
    ).all();
    return json({ invites: result.results || [] });
  }

  if (request.method === "GET" && path === "/v1/admin/clients") {
    const result = await env.DB.prepare(
      `SELECT client_id, contributor_id, client_name, enabled, created_at, last_seen_at
       FROM clients ORDER BY created_at DESC LIMIT 500`
    ).all();
    return json({ clients: result.results || [] });
  }

  const revoke = path.match(/^\/v1\/admin\/invites\/([^/]+)\/revoke$/);
  if (request.method === "POST" && revoke) {
    await env.DB.prepare("UPDATE invites SET revoked=1 WHERE id=?").bind(revoke[1]).run();
    return json({ ok: true, invite_id: revoke[1], revoked: true });
  }

  const clientAction = path.match(/^\/v1\/admin\/clients\/([^/]+)\/(disable|enable|rotate)$/);
  if (request.method === "POST" && clientAction) {
    const clientId = clientAction[1];
    const action = clientAction[2];
    if (action === "disable" || action === "enable") {
      await env.DB.prepare("UPDATE clients SET enabled=? WHERE client_id=?")
        .bind(action === "enable" ? 1 : 0, clientId).run();
      return json({ ok: true, client_id: clientId, action });
    }
    const token = randomSecret();
    await env.DB.prepare("UPDATE clients SET token_hash=? WHERE client_id=?")
      .bind(await sha256(token), clientId).run();
    return json({ ok: true, client_id: clientId, action, client_token: token });
  }

  return json({ error: "admin route not found" }, 404);
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      const path = url.pathname;

      if (request.method === "GET" && path === "/v1/health") {
        await env.DB.prepare("SELECT 1 AS ok").first();
        return json({
          ok: true,
          service: "Crypto TradeStation CloudShare",
          protocol_version: PROTOCOL_VERSION,
          d1: true,
          r2: true,
          now: nowIso()
        });
      }

      if (path.startsWith("/v1/admin/")) return await adminRoutes(request, env, path);
      if (request.method === "POST" && path === "/v1/register") return await handleRegister(request, env);

      const auth = await requireClient(request, env);
      if (auth.error) return auth.error;
      const client = auth.client;

      if (request.method === "GET" && path === "/v1/client/status") {
        return json({
          client_id: client.client_id,
          contributor_id: client.contributor_id,
          client_name: client.client_name,
          enabled: Number(client.enabled) === 1
        });
      }

      if (request.method === "GET" && path === "/v1/intelligence/status") {
        const count = await env.DB.prepare("SELECT COUNT(*) AS count FROM events").first();
        return json({ protocol_version: PROTOCOL_VERSION, event_count: Number(count?.count || 0) });
      }

      if (request.method === "POST" && path === "/v1/events/batch") return await handleBatch(request, env, client);
      if (request.method === "GET" && path === "/v1/intelligence/events") return await handleIntelligence(request, env);
      if (request.method === "POST" && path === "/v1/bootstrap") return await handleBootstrap(request, env, client);

      return json({ error: "route not found" }, 404);
    } catch (error) {
      return json({ error: String(error?.message || error).slice(0, 1000) }, 500);
    }
  }
};
