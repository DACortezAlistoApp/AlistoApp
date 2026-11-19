import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    enc.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, enc.encode(message));
  return Array.from(new Uint8Array(sig))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function constantTimeEqual(a: string, b: string): boolean {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function parsePayMongoSigHeader(headerVal: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of headerVal.split(",")) {
    const idx = part.indexOf("=");
    if (idx > 0) {
      out[part.slice(0, idx).trim()] = part.slice(idx + 1).trim();
    }
  }
  return out;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  try {
    const rawBody = await req.text();
    const sigHeader = req.headers.get("paymongo-signature") ?? "";
    const evt = JSON.parse(rawBody);

    const eventAttrs = evt?.data?.attributes ?? {};
    const eventType = String(eventAttrs.type ?? "");
    const livemode = Boolean(eventAttrs.livemode);

    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const admin = createClient(supabaseUrl, serviceRoleKey);

    // Load matching Test or Live webhook signing secret from SuperAdmin Vault
    const { data: cfg } = await admin
      .from("payment_gateway_config")
      .select("test_webhook_secret, live_webhook_secret")
      .eq("id", 1)
      .single();

    const webhookSecret = String(livemode ? cfg?.live_webhook_secret : cfg?.test_webhook_secret ?? "").trim();
    if (!webhookSecret) {
      return new Response("Webhook signing secret not configured", { status: 500 });
    }

    const sigParts = parsePayMongoSigHeader(sigHeader);
    const timestamp = sigParts["t"] ?? "";
    const expectedProvidedSig = livemode ? (sigParts["li"] ?? "") : (sigParts["te"] ?? "");
    const computedSig = await hmacSha256Hex(webhookSecret, `${timestamp}.${rawBody}`);

    if (!timestamp || !constantTimeEqual(computedSig, expectedProvidedSig)) {
      return new Response("Invalid PayMongo webhook signature", { status: 401 });
    }

    const resource = eventAttrs.data ?? {};
    const resAttrs = resource.attributes ?? {};
    const txRef = String(
      resAttrs.metadata?.tx_ref ??
      resAttrs.reference_number ??
      resAttrs.payment_intent?.attributes?.metadata?.tx_ref ??
      ""
    ).trim();
    const providerRef = String(resource.id ?? "");

    if (!txRef) {
      return new Response(JSON.stringify({ status: "IGNORED", reason: "No Alisto tx_ref in payload" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    if (eventType === "checkout_session.payment.paid" || eventType === "payment.paid") {
      const { data, error } = await admin.rpc("service_settle_paymongo_payment_rpc", {
        p_tx_ref: txRef,
        p_provider_reference: providerRef,
        p_webhook_payload: evt,
      });
      if (error) {
        return new Response(JSON.stringify({ status: "ERROR", message: error.message }), {
          status: 500,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(JSON.stringify({ status: "SETTLED", result: data }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    if (eventType === "payment.failed" || eventType === "checkout_session.payment.failed") {
      await admin
        .from("payment_transactions")
        .update({ status: "FAILED", webhook_payload: evt })
        .eq("tx_ref", txRef)
        .neq("status", "PAID");
    }

    return new Response(JSON.stringify({ status: "ACK", eventType }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  } catch (err) {
    return new Response(JSON.stringify({ status: "ERROR", message: String(err) }), {
      status: 500,
      headers: { "Content-Type": "application/json" },
    });
  }
});
