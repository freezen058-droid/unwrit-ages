// Solana RPC for the game, at https://unwritages.pages.dev/rpc  (Pages Function: this folder sits at
// the repository root because `wrangler pages deploy website`, run from here, reads ./functions)
//
// The game needs a mainnet RPC it can rely on, and the reliable ones (Helius) take an API key.
// A key inside the APK is a key anyone can unzip and spend, so the game calls this instead and
// the key stays here, as a Pages secret:
//
//     npx wrangler pages secret put HELIUS_API_KEY --project-name unwritages
//
// Only the methods the game sends are forwarded, one request at a time, so this cannot be
// used as a general-purpose RPC on someone else's quota. getSignatureStatuses (09-26): the
// anchored start waits for its memo to be confirmed before the map is made from its signature.
// getSignaturesForAddress (cloud saves): the list of a wallet's, or everyone's shared, recorded
// saves is read from those transactions' memos. getTransaction (10-01, the shared-save gallery): a
// tip's memo says how much it was, the transaction's token balances say how much the author got -
// only a tip that paid what it claims is counted. getTokenAccountsByOwner (10-01): whether a save's
// author holds a Seeker Genesis Token, for the gallery's Seeker mark.

const ALLOWED = new Set(["getAccountInfo", "getLatestBlockhash", "sendTransaction", "getSignatureStatuses",
  "getSignaturesForAddress", "getTransaction", "getBlockHeight",
  "getTokenAccountsByOwner"]);
const MAX_BODY = 8 * 1024; // a Solana transaction is at most 1232 bytes, ~1.7 KB as base64

function error(status, message, id = null) {
  return new Response(
    JSON.stringify({ jsonrpc: "2.0", id, error: { code: -32600, message } }),
    { status, headers: { "content-type": "application/json" } }
  );
}

export async function onRequestPost({ request, env }) {
  if (!env.HELIUS_API_KEY) return error(503, "RPC not configured");

  const text = await request.text();
  if (text.length > MAX_BODY) return error(413, "Request too large");

  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return error(400, "Invalid JSON");
  }
  // One call per request: a batch would let one POST spend many credits.
  if (Array.isArray(body) || typeof body !== "object" || body === null)
    return error(400, "Single JSON-RPC request expected");
  if (!ALLOWED.has(body.method)) return error(403, "Method not allowed", body.id ?? null);

  const upstream = await fetch(
    `https://mainnet.helius-rpc.com/?api-key=${env.HELIUS_API_KEY}`,
    { method: "POST", headers: { "content-type": "application/json" }, body: text }
  );
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { "content-type": "application/json" },
  });
}

export function onRequest() {
  return error(405, "POST only");
}
