import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

function base64UrlEncode(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const cleaned = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const bin = atob(cleaned);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}

async function getGoogleAccessToken(clientEmail: string, privateKeyPem: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64UrlEncode(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64UrlEncode(JSON.stringify({
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const signingInput = `${header}.${claim}`;

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", cryptoKey, new TextEncoder().encode(signingInput));
  const jwt = `${signingInput}.${base64UrlEncode(new Uint8Array(sig))}`;

  const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  const tokenJson = await tokenResp.json();
  if (!tokenResp.ok || !tokenJson.access_token) {
    throw new Error(tokenJson.error_description ?? "Failed to mint Google FCM OAuth token");
  }
  return tokenJson.access_token;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const fcmProjectId = Deno.env.get("FCM_PROJECT_ID") ?? "";
    const fcmClientEmail = Deno.env.get("FCM_CLIENT_EMAIL") ?? "";
    const fcmPrivateKey = (Deno.env.get("FCM_PRIVATE_KEY") ?? "").replace(/\\n/g, "\n");

    if (!fcmProjectId || !fcmClientEmail || !fcmPrivateKey) {
      return new Response(JSON.stringify({
        status: "NOT_CONFIGURED",
        message: "Set FCM_PROJECT_ID, FCM_CLIENT_EMAIL, and FCM_PRIVATE_KEY in Supabase Edge Secrets to enable background push delivery.",
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    }

    const admin = createClient(supabaseUrl, serviceRoleKey);
    const { data: queued, error: qErr } = await admin
      .from("alisto_push_outbox")
      .select("id, recipient_id, event_type, title, body, payload")
      .eq("push_status", "QUEUED")
      .order("created_at", { ascending: true })
      .limit(50);

    if (qErr) throw qErr;
    if (!queued || queued.length === 0) {
      return new Response(JSON.stringify({ status: "OK", processed: 0 }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    const accessToken = await getGoogleAccessToken(fcmClientEmail, fcmPrivateKey);
    let sentCount = 0;
    let failedCount = 0;

    for (const row of queued) {
      const { data: u } = await admin
        .from("users")
        .select("device_fcm_token")
        .eq("id", row.recipient_id)
        .maybeSingle();

      if (!u?.device_fcm_token) continue;

      const fcmResp = await fetch(`https://fcm.googleapis.com/v1/projects/${fcmProjectId}/messages:send`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token: u.device_fcm_token,
            notification: { title: row.title, body: row.body },
            data: {
              event_type: String(row.event_type ?? ""),
              outbox_id: String(row.id ?? ""),
              payload_json: JSON.stringify(row.payload ?? {}),
            },
            android: { priority: "high" },
          },
        }),
      });

      if (fcmResp.ok) {
        sentCount++;
        await admin.from("alisto_push_outbox").update({ push_status: "SENT" }).eq("id", row.id);
      } else {
        failedCount++;
        await admin.from("alisto_push_outbox").update({ push_status: "FAILED" }).eq("id", row.id);
      }
    }

    return new Response(JSON.stringify({ status: "OK", sent: sentCount, failed: failedCount }), {
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
