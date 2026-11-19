import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function jsonResponse(body: Record<string, unknown>, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
  });
}

async function sha256Hex(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }
  if (req.method !== "POST") {
    return jsonResponse({ status: "ERROR", message: "Method not allowed" }, 405);
  }

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    const admin = createClient(supabaseUrl, serviceRoleKey);

    const body = await req.json();
    const sessionToken = String(body.session_token ?? "").trim();
    const purpose = String(body.purpose ?? "WALLET_TOPUP").trim().toUpperCase();
    const tierCode = body.tier_code ? String(body.tier_code).trim().toUpperCase() : null;
    const requestedAmount = Number(body.amount_php ?? 0);

    if (sessionToken.length < 32) {
      return jsonResponse({ status: "UNAUTHORIZED", message: "Valid session token required." }, 401);
    }

    // Validate caller session token against user_sessions SHA-256 vault
    const tokenHash = await sha256Hex(sessionToken);
    const { data: sess, error: sessErr } = await admin
      .from("user_sessions")
      .select("id, user_id, expires_at, revoked_at")
      .eq("token_hash", tokenHash)
      .is("revoked_at", null)
      .gt("expires_at", new Date().toISOString())
      .maybeSingle();

    if (sessErr || !sess) {
      return jsonResponse({ status: "UNAUTHORIZED", message: "Session expired or invalid." }, 401);
    }

    const { data: user } = await admin
      .from("users")
      .select("id, full_name, email, phone, account_frozen")
      .eq("id", sess.user_id)
      .single();

    if (!user || user.account_frozen) {
      return jsonResponse({ status: "UNAUTHORIZED", message: "Account unavailable or suspended." }, 403);
    }

    // Load active mode & credentials directly from SuperAdmin Payment Gateway Vault
    const { data: cfg, error: cfgErr } = await admin
      .from("payment_gateway_config")
      .select("*")
      .eq("id", 1)
      .single();

    if (cfgErr || !cfg) {
      return jsonResponse({ status: "ERROR", message: "Payment gateway configuration missing." }, 500);
    }

    if (cfg.payment_mode === "MANUAL") {
      return jsonResponse({
        status: "MANUAL_MODE",
        message: "Platform is currently in MANUAL payment mode. Submit your receipt reference instead.",
      }, 400);
    }

    const isLive = cfg.payment_mode === "PAYMONGO_LIVE";
    const apiBaseUrl = String(isLive ? cfg.live_api_base_url : cfg.test_api_base_url).replace(/\/+$/, "");
    const secretKey = String(isLive ? cfg.live_secret_key : cfg.test_secret_key).trim();

    if (!secretKey) {
      return jsonResponse({
        status: "ERROR",
        message: `PayMongo ${isLive ? "Live" : "Test"} Secret Key is not configured in SuperAdmin Vault.`,
      }, 500);
    }

    // Determine authoritative PHP amount
    let finalAmountPhp = Math.round(requestedAmount * 100) / 100;
    let itemLabel = "Alisto Prepaid Wallet Top-Up";

    if (purpose === "SUBSCRIPTION_PASS" && tierCode) {
      const { data: tierRow } = await admin
        .from("subscription_tiers")
        .select("*")
        .eq("tier_code", tierCode)
        .maybeSingle();
      if (tierRow) {
        finalAmountPhp = Number(tierRow.fee_php ?? tierRow.monthly_fee_php ?? finalAmountPhp);
        itemLabel = `Alisto Subscription Pass (${tierCode})`;
      }
    }

    if (!Number.isFinite(finalAmountPhp) || finalAmountPhp < 20) {
      return jsonResponse({ status: "ERROR", message: "Minimum PayMongo checkout amount is PHP 20.00." }, 400);
    }

    // Create PENDING payment transaction row first to obtain authoritative tx_ref
    const { data: tx, error: txErr } = await admin
      .from("payment_transactions")
      .insert({
        user_id: user.id,
        payment_mode: cfg.payment_mode,
        purpose,
        tier_code: tierCode,
        amount_php: finalAmountPhp,
        status: "PENDING",
        notes: `PayMongo ${isLive ? "Live" : "Test"} Checkout initiated`,
      })
      .select("*")
      .single();

    if (txErr || !tx) {
      return jsonResponse({ status: "ERROR", message: txErr?.message ?? "Failed to log transaction." }, 500);
    }

    const authHeader = "Basic " + btoa(`${secretKey}:`);
    const returnUrl = cfg.checkout_return_url || "https://dacortezalistoapp.github.io/AlistoApp/";

    const pmResp = await fetch(`${apiBaseUrl}/checkout_sessions`, {
      method: "POST",
      headers: {
        Authorization: authHeader,
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify({
        data: {
          attributes: {
            send_email_receipt: true,
            show_description: true,
            show_line_items: true,
            description: `${itemLabel} [${tx.tx_ref}]`,
            reference_number: tx.tx_ref,
            payment_method_types: ["qrph", "gcash", "paymaya"],
            billing: {
              name: user.full_name || "Alisto Contractor",
              email: user.email || "payments@alisto.ph",
              phone: user.phone || "",
            },
            line_items: [
              {
                currency: "PHP",
                amount: Math.round(finalAmountPhp * 100),
                name: itemLabel,
                quantity: 1,
              },
            ],
            metadata: {
              tx_ref: tx.tx_ref,
              user_id: user.id,
              purpose,
              tier_code: tierCode ?? "",
            },
            success_url: returnUrl,
            cancel_url: returnUrl,
          },
        },
      }),
    });

    const pmData = await pmResp.json();
    if (!pmResp.ok || !pmData?.data?.attributes?.checkout_url) {
      const errMsg = pmData?.errors?.[0]?.detail ?? "PayMongo rejected checkout session creation.";
      await admin
        .from("payment_transactions")
        .update({ status: "FAILED", notes: errMsg, webhook_payload: pmData })
        .eq("id", tx.id);
      return jsonResponse({ status: "ERROR", message: errMsg }, 502);
    }

    const checkoutUrl = pmData.data.attributes.checkout_url;
    const providerId = pmData.data.id;

    await admin
      .from("payment_transactions")
      .update({
        provider_reference: providerId,
        checkout_url: checkoutUrl,
      })
      .eq("id", tx.id);

    return jsonResponse({
      status: "SUCCESS",
      payment_mode: cfg.payment_mode,
      tx_ref: tx.tx_ref,
      provider_reference: providerId,
      amount_php: finalAmountPhp,
      checkout_url: checkoutUrl,
    });
  } catch (err) {
    return jsonResponse({ status: "ERROR", message: String(err) }, 500);
  }
});
