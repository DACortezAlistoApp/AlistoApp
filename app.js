// ============================================================================
// ALISTO NETWORK PH - V3 11-ENGINE MASTER COMMAND & HYBRID MONETIZATION CONSOLE
// Doc Refs: ALISTO-ARCH-WORKFLOW-2026-V2 & ALISTO-ARCH-MASTER-SPEC-2026-V3
// Author & Chief Systems Architect: D. A. Cortez
// ============================================================================

const _K_U = atob("aHR0cHM6Ly9mbWpqeWhtdGRzdmh1ZmtneWpwci5zdXBhYmFzZS5jbw==");
const _K_A = atob("ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBjM01pT2lKemRYQmhZbUZ6WlNJc0luSmxaaUk2SW1adGFtcDVhRzEwWkhOMmFIVm1hMmQ1YW5CeUlpd2ljbTlzWlNJNkltRnViMjRpTENKcFlYUWlPakUzT1RRNU1URTVPRElzSW1WNGNDSTZNakV4TURRNE56azRNbjAueGV2a21NQkFuSVVjTktrcDM0UFRubWxrLTM4NVNVd3B3aGJWMEtheWd6cw==");
let supabaseClient = null;
let currentAuthUser = null;
let activeProfileModalUserId = null;
const SESSION_STORAGE_KEY = 'ALISTO_AUTH_SESSION_V1';

let currentSessionToken = sessionStorage.getItem('alisto_session_token') || null;
let securePollTimer = null;

function getActiveSessionToken() {
  return currentSessionToken || sessionStorage.getItem('alisto_session_token') || '';
}

function setActiveSessionToken(token) {
  currentSessionToken = token || null;
  if (currentSessionToken) {
    sessionStorage.setItem('alisto_session_token', currentSessionToken);
  } else {
    sessionStorage.removeItem('alisto_session_token');
  }
}

function startSecureConsolePolling() {
  if (securePollTimer) clearInterval(securePollTimer);
  securePollTimer = setInterval(() => {
    if (currentAuthUser && getActiveSessionToken()) {
      syncFromSupabase();
    }
  }, 20000);
}

const consoleState = {
  globalCommissionRate: 0.12,
  maturityHours: 72,
  ledgerHealth: null,
  paymentGatewayConfig: null,
  paymentTransactions: [],
  liveTelemetryPins: [],
  pushOutboxRecent: [],
  ltfrbSafetyEvents: [],
  verticals: [],
  heatmapClusters: [],
  bookingBids: [],
  servicePricingMatrix: [],
  subscriptionTiers: [],
  contractorSubscriptions: [],
  contractorProfiles: [],
  users: [],
  bookings: [],
  ledger: [],
  ratings: [],
  messages: [],
  telemetry: [],
  moderationRules: [],
  moderationAuditLogs: [],
  operatorAuditLogs: []
};

// ============================================================================
// 1. DATABASE AUTHENTICATION & SESSION RBAC ENFORCEMENT
// ============================================================================

function toggleAuthPasswordVisibility() {
  const pw = document.getElementById('authPasswordInput');
  if (!pw) return;
  pw.type = pw.type === 'password' ? 'text' : 'password';
}

async function submitConsoleLogin() {
  const idInput = document.getElementById('authIdentifierInput');
  const pwInput = document.getElementById('authPasswordInput');
  const errBanner = document.getElementById('authErrorBanner');
  const submitBtn = document.getElementById('authSubmitBtn');

  errBanner.style.display = 'none';
  const identifier = (idInput.value || '').trim();
  const password = pwInput.value || '';

  if (!identifier || !password) {
    errBanner.textContent = 'Please enter both your Username/Phone/Email and Password.';
    errBanner.style.display = 'block';
    return;
  }

  if (!supabaseClient) {
    errBanner.textContent = 'Supabase client is initializing. Please try again in a moment.';
    errBanner.style.display = 'block';
    return;
  }

  submitBtn.disabled = true;
  submitBtn.textContent = 'VERIFYING AGAINST PUBLIC.USERS...';

  try {
    const { data, error } = await supabaseClient.rpc('authenticate_user_secure_rpc', {
      p_identifier: identifier,
      p_password: password
    });

    submitBtn.disabled = false;
    submitBtn.textContent = 'AUTHENTICATE & UNLOCK CONSOLE';

    if (error) {
      errBanner.textContent = 'Database Auth RPC Error: ' + error.message;
      errBanner.style.display = 'block';
      return;
    }

    if (!data || data.status !== 'SUCCESS' || !data.user) {
      errBanner.textContent = (data && data.message) ? data.message : 'Invalid credentials or unauthorized account.';
      errBanner.style.display = 'block';
      return;
    }

    currentAuthUser = data.user;
    setActiveSessionToken(data.session_token || null);
    startSecureConsolePolling();
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(currentAuthUser));
    pwInput.value = '';
    await unlockConsoleForAuthenticatedUser();
  } catch (err) {
    submitBtn.disabled = false;
    submitBtn.textContent = 'AUTHENTICATE & UNLOCK CONSOLE';
    errBanner.textContent = 'Authentication error: ' + err.message;
    errBanner.style.display = 'block';
  }
}

async function unlockConsoleForAuthenticatedUser() {
  if (!currentAuthUser) return;

  document.getElementById('authGateOverlay').style.display = 'none';
  document.getElementById('protectedConsoleShell').style.display = 'block';

  const uLabel = document.getElementById('activeSessionUserLabel');
  const rBadge = document.getElementById('activeSessionRoleBadge');
  if (uLabel) {
    uLabel.textContent = `${currentAuthUser.full_name} (@${currentAuthUser.username || currentAuthUser.role})`;
  }
  if (rBadge) {
    rBadge.textContent = `ROLE: ${currentAuthUser.role.toUpperCase()}`;
  }

  applyRoleBasedAccessControl();
  await syncFromSupabase();
  subscribeRealtimeKernel();
}

function applyRoleBasedAccessControl() {
  if (!currentAuthUser) return;
  const role = currentAuthUser.role;

  const tabOp = document.getElementById('tabBtnOperatorOps');
  const tabSuper = document.getElementById('tabBtnSuperAdmin');
  const tabPay = document.getElementById('tabBtnPayMongo');
  const tabLgu = document.getElementById('tabBtnLguAdmin');
  const tabApk = document.getElementById('tabBtnApkPortal');
  const onbAdminOpt = document.getElementById('onbAdminOption');

  if (role === 'super_admin') {
    tabOp.style.display = 'inline-block';
    tabSuper.style.display = 'inline-block';
    if (tabPay) tabPay.style.display = 'inline-block';
    tabLgu.style.display = 'inline-block';
    tabApk.style.display = 'inline-block';
    if (onbAdminOpt) onbAdminOpt.disabled = false;
    switchConsoleView('operator_ops');
  } else if (role === 'admin') {
    tabOp.style.display = 'inline-block';
    tabSuper.style.display = 'none';
    if (tabPay) tabPay.style.display = 'none';
    tabLgu.style.display = 'inline-block';
    tabApk.style.display = 'inline-block';
    if (onbAdminOpt) {
      onbAdminOpt.disabled = true;
      if (document.getElementById('onbRole').value === 'admin') {
        document.getElementById('onbRole').value = 'contractor';
      }
    }
    switchConsoleView('operator_ops');
  } else {
    tabOp.style.display = 'none';
    tabSuper.style.display = 'none';
    if (tabPay) tabPay.style.display = 'none';
    tabLgu.style.display = 'none';
    tabApk.style.display = 'inline-block';
    switchConsoleView('apk_portal');
  }
}

function logoutConsoleSession() {
  const tok = getActiveSessionToken();
  if (tok && typeof supabaseClient !== 'undefined') {
    supabaseClient.rpc('logout_session_rpc', { p_session_token: tok }).catch(() => {});
  }
  setActiveSessionToken(null);
  if (securePollTimer) { clearInterval(securePollTimer); securePollTimer = null; }

  currentAuthUser = null;
  sessionStorage.removeItem(SESSION_STORAGE_KEY);
  document.getElementById('protectedConsoleShell').style.display = 'none';
  document.getElementById('authGateOverlay').style.display = 'flex';
  const errBanner = document.getElementById('authErrorBanner');
  if (errBanner) errBanner.style.display = 'none';
}

function switchConsoleView(viewKey) {
  if (!currentAuthUser) return;
  const role = currentAuthUser.role;

  if ((viewKey === 'superadmin' || viewKey === 'paymongo_gateway') && role !== 'super_admin') {
    alert('RBAC Restriction: Only SuperAdmin can access the Universal Ledger Health, Subscription Pricing & Vertical Rollout Control Center.');
    return;
  }
  if ((viewKey === 'operator_ops' || viewKey === 'lgu_admin') && !['super_admin', 'admin'].includes(role)) {
    viewKey = 'apk_portal';
  }

  document.querySelectorAll('.role-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.view === viewKey);
  });
  document.querySelectorAll('.role-view').forEach(sec => {
    sec.classList.toggle('active', sec.id === 'view-' + viewKey);
  });
  renderAll();
  if (viewKey === 'operator_ops' && typeof alistoOpsMap !== 'undefined' && alistoOpsMap) {
    setTimeout(() => alistoOpsMap.invalidateSize(), 150);
  }
}

// ============================================================================
// 2. SUPABASE DATA SYNC & REALTIME SUBSCRIPTIONS
// ============================================================================

function formatTs(ts) {
  if (!ts) return '— (Not Reached)';
  const d = new Date(ts);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
}

function getUserById(uid) {
  if (!uid) return null;
  return consoleState.users.find(u => u.id === uid) || null;
}

function getContractorProfile(uid) {
  if (!uid) return null;
  return consoleState.contractorProfiles.find(p => p.user_id === uid) || null;
}

function getActiveSubscription(uid) {
  if (!uid) return null;
  const now = new Date();
  return consoleState.contractorSubscriptions.find(
    s => s.contractor_id === uid && s.status === 'ACTIVE' && new Date(s.expires_at) > now
  ) || null;
}

function getUserLabel(uid) {
  const u = getUserById(uid);
  return u ? `${u.full_name} (@${u.username || u.role})` : (uid ? uid.slice(0, 8) : 'Unassigned');
}

function getInfractionCount(uid) {
  return consoleState.moderationAuditLogs.filter(l => l.sender_id === uid).length;
}

function splitFirstLastName(u) {
  const fn = u.first_name || (u.full_name ? u.full_name.trim().split(' ')[0] : '');
  const ln = u.last_name !== undefined && u.last_name !== null
    ? u.last_name
    : (u.full_name && u.full_name.trim().includes(' ')
        ? u.full_name.trim().split(' ').slice(1).join(' ')
        : '');
  return { firstName: fn, lastName: ln };
}

async function syncFromSupabase() {
  const token = getActiveSessionToken();
  if (!token) return;

  try {
    const { data, error } = await supabaseClient.rpc('secure_get_console_snapshot_rpc', {
      p_session_token: token
    });

    if (error) {
      console.error('Secure console snapshot error:', error);
      return;
    }

    if (!data || data.status === 'UNAUTHORIZED') {
      alert('Your session has expired or was revoked. Please sign in again.');
      logoutConsoleSession();
      return;
    }

    if (data.actor) currentAuthUser = data.actor;
    consoleState.verticals = data.verticals || [];
    consoleState.servicePricingMatrix = data.service_pricing_matrix || [];
    consoleState.heatmapClusters = data.heatmap_clusters || [];
    consoleState.bookingBids = data.booking_bids || [];
    consoleState.ledgerHealth = data.ledger_health || null;
    consoleState.paymentGatewayConfig = data.payment_gateway_config || null;
    consoleState.paymentTransactions = data.payment_transactions || [];
    consoleState.liveTelemetryPins = data.live_telemetry_pins || [];
    consoleState.pushOutboxRecent = data.push_outbox_recent || [];
    consoleState.ltfrbSafetyEvents = data.ltfrb_safety_events || [];
    consoleState.subscriptionTiers = data.subscription_tiers || [];
    consoleState.contractorSubscriptions = data.contractor_subscriptions || [];
    consoleState.contractorProfiles = data.contractor_profiles || [];
    consoleState.users = data.users || [];
    consoleState.bookings = data.bookings || [];
    consoleState.ledger = data.credit_ledger || [];
    consoleState.ratings = data.booking_ratings || [];
    consoleState.messages = data.booking_messages || [];
    consoleState.telemetry = data.booking_telemetry || [];
    consoleState.moderationRules = data.moderation_rules || [];
    consoleState.moderationAuditLogs = data.moderation_audit_logs || [];
    consoleState.operatorAuditLogs = data.operator_audit_logs || [];

    const cfgRows = data.system_config || [];
    const commRow = cfgRows.find(c => c.config_key === 'platform_commission_rate');
    if (commRow && commRow.config_value) {
      consoleState.globalCommissionRate = parseFloat(commRow.config_value) || 0.12;
    }
    const matRow = cfgRows.find(c => c.config_key === 'skills_job_maturity_hours' || c.config_key === 'job_maturity_hours');
    if (matRow && matRow.config_value) {
      consoleState.maturityHours = parseInt(matRow.config_value, 10) || 72;
    }

    renderAll();
  } catch (err) {
    console.error('syncFromSupabase unexpected error:', err);
  }
}

let realtimeSubscribed = false;
function subscribeRealtimeKernel() {
  if (!supabaseClient || realtimeSubscribed) return;
  realtimeSubscribed = true;
  supabaseClient
    .channel('alisto-v3-live')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'service_verticals' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'service_pricing_matrix' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'users' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'contractor_profiles' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'subscription_tiers' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'contractor_subscriptions' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'bookings' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'credit_ledger' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'booking_ratings' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'booking_messages' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'moderation_audit_logs' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'operator_audit_logs' }, () => syncFromSupabase())
    .subscribe();
}

function renderVerticalStrip() {
  const strip = document.getElementById('verticalStatusStrip');
  if (!strip) return;
  strip.innerHTML = consoleState.verticals.map(v => `
    <div class="v-pill">
      <span class="v-dot ${v.is_enabled ? 'on' : 'off'}"></span>
      <strong>${v.display_name}:</strong>
      <span>${v.is_enabled ? 'ACTIVE ON KOTLIN APKs' : 'LOCKED / DEACTIVATED'}</span>
    </div>
  `).join('');
}

// ============================================================================
// 3. CLIENT-SIDE MEDIA COMPRESSION (SECTION 4.2 OF V2/V3 SPEC)
// - Profile Pictures: VGA Standard (640 x 480 px), JPEG 68% quality (<80 KB)
// - Compliance Docs: Low-to-Medium Readable (Max 1024 px long edge), JPEG 65% quality (120-250 KB)
// ============================================================================

function compressMediaBySpec(file, assetCategory = 'COMPLIANCE_DOC') {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = e => {
      const img = new Image();
      img.onload = () => {
        let targetW = img.width;
        let targetH = img.height;
        let quality = 0.65;

        if (assetCategory === 'PROFILE_VGA') {
          const maxW = 640;
          const maxH = 480;
          const scale = Math.min(1, maxW / img.width, maxH / img.height);
          targetW = Math.round(img.width * scale);
          targetH = Math.round(img.height * scale);
          quality = 0.68;
        } else {
          const maxEdge = 1024;
          const longest = Math.max(img.width, img.height);
          const scale = Math.min(1, maxEdge / longest);
          targetW = Math.round(img.width * scale);
          targetH = Math.round(img.height * scale);
          quality = 0.65;
        }

        const canvas = document.createElement('canvas');
        canvas.width = targetW;
        canvas.height = targetH;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, targetW, targetH);
        const dataUrl = canvas.toDataURL('image/jpeg', quality);
        const approxKb = Math.round((dataUrl.length * 0.75) / 1024);
        resolve({ dataUrl, width: targetW, height: targetH, approxKb });
      };
      img.onerror = reject;
      img.src = e.target.result;
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

// ============================================================================
// 4. USERNAME & FIRST/LAST NAME COMBO LOOKUP + KYC PIPELINE + ONBOARDING
// ============================================================================

function toggleOnboardContractorFields() {
  const role = document.getElementById('onbRole').value;
  const sec = document.getElementById('onbContractorSection');
  if (sec) sec.style.display = role === 'contractor' ? 'block' : 'none';
}

function toggleConditionalTransportFields() {
  const del = document.getElementById('onbOptDelivery')?.checked;
  const ride = document.getElementById('onbOptRideHailing')?.checked;
  const box = document.getElementById('onbTransportCredentialsBox');
  if (box) box.style.display = (del || ride) ? 'block' : 'none';
}

function useDeviceGpsForOnboarding() {
  if (navigator.geolocation) {
    navigator.geolocation.getCurrentPosition(pos => {
      document.getElementById('onbHomeLat').value = pos.coords.latitude.toFixed(5);
      document.getElementById('onbHomeLng').value = pos.coords.longitude.toFixed(5);
      alert(`Snapped Home Pin to real-time GPS: ${pos.coords.latitude.toFixed(5)}, ${pos.coords.longitude.toFixed(5)}`);
    }, () => {
      document.getElementById('onbHomeLat').value = (15.1450 + Math.random() * 0.008).toFixed(5);
      document.getElementById('onbHomeLng').value = (120.5887 + Math.random() * 0.008).toFixed(5);
    });
  }
}

function renderOperatorKpis() {
  const strip = document.getElementById('operatorKpiStrip');
  if (!strip) return;
  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  const pendingKyc = contractors.filter(c => c.account_status === 'PENDING_REVIEW' || c.account_status === 'RESUBMISSION_REQUIRED');
  const customers = consoleState.users.filter(u => u.role === 'customer');
  const activeSubs = consoleState.contractorSubscriptions.filter(s => s.status === 'ACTIVE' && new Date(s.expires_at) > new Date());
  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);

  strip.innerHTML = `
    <div class="kpi-card">
      <div class="kpi-label">Registered Contractors</div>
      <div class="kpi-value">${contractors.length}</div>
      <div class="kpi-sub">${contractors.filter(c => c.account_status === 'ACTIVE').length} ACTIVE | ${pendingKyc.length} Pending KYC</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Registered Customers</div>
      <div class="kpi-value">${customers.length}</div>
      <div class="kpi-sub">GPS Home Anchors Saved</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Active Subscription Passes</div>
      <div class="kpi-value" style="color:var(--emerald-ok)">${activeSubs.length}</div>
      <div class="kpi-sub">0.00% Commission Window Active</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Pay-As-You-Go Cap</div>
      <div class="kpi-value" style="color:var(--amber-core)">≤ ${globalPct}%</div>
      <div class="kpi-sub">+₱25 Bonus on ≥₱500 Top-Up</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Pending KYC / Resubmit</div>
      <div class="kpi-value" style="color:${pendingKyc.length > 0 ? 'var(--amber-core)' : 'var(--emerald-ok)'}">${pendingKyc.length}</div>
      <div class="kpi-sub">Manual Call & Doc Review</div>
    </div>
  `;
}

function renderOperatorUsersTable() {
  const tbody = document.getElementById('operatorUsersTable');
  const countEl = document.getElementById('userLookupMatchCount');
  if (!tbody || !currentAuthUser) return;

  const rawQuery = (document.getElementById('userLookupQueryInput')?.value || '').toLowerCase().trim();
  const cleanQuery = rawQuery.replace(/^@/, '');
  const roleFilter = document.getElementById('userRoleFilter')?.value || 'ALL';
  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);

  const filtered = consoleState.users.filter(u => {
    if (roleFilter === 'FROZEN' && !u.account_frozen && u.account_status !== 'SUSPENDED') return false;
    if (roleFilter === 'PENDING_KYC' && !['PENDING_REVIEW', 'RESUBMISSION_REQUIRED'].includes(u.account_status)) return false;
    if (!['ALL', 'FROZEN', 'PENDING_KYC'].includes(roleFilter) && u.role !== roleFilter) return false;
    if (!cleanQuery) return true;

    const { firstName, lastName } = splitFirstLastName(u);
    const uname = (u.username || '').toLowerCase();
    const nick = (u.nickname || '').toLowerCase();
    const fullCombo = `${firstName} ${lastName}`.toLowerCase().trim();
    const revCombo = `${lastName} ${firstName}`.toLowerCase().trim();
    const fullName = (u.full_name || '').toLowerCase();
    const phone = (u.phone || '').toLowerCase();

    const tokens = cleanQuery.split(/\s+/).filter(Boolean);
    const tokenMatch = tokens.every(t =>
      uname.includes(t) ||
      nick.includes(t) ||
      firstName.toLowerCase().includes(t) ||
      lastName.toLowerCase().includes(t) ||
      fullName.includes(t) ||
      phone.includes(t)
    );

    return uname.includes(cleanQuery) ||
           fullCombo.includes(cleanQuery) ||
           revCombo.includes(cleanQuery) ||
           fullName.includes(cleanQuery) ||
           tokenMatch;
  });

  if (countEl) {
    countEl.textContent = cleanQuery
      ? `Showing ${filtered.length} matching account(s) for query "${rawQuery}" — Click any @username badge to open their full Profile Command Modal:`
      : `Showing all ${filtered.length} account(s) — Click any @username badge to open their full Profile Command Modal:`;
  }

  tbody.innerHTML = filtered.map(u => {
    const { firstName, lastName } = splitFirstLastName(u);
    const cProf = getContractorProfile(u.id);
    const activeSub = getActiveSubscription(u.id);
    const certs = Array.isArray(u.certifications_docs) ? u.certifications_docs : [];
    const userRatings = consoleState.ratings.filter(r => r.ratee_id === u.id);
    const avgStars = userRatings.length > 0
      ? (userRatings.reduce((acc, r) => acc + Number(r.stars || 5), 0) / userRatings.length).toFixed(2)
      : Number(u.rating_avg || 5.0).toFixed(2);
    const ratingCnt = userRatings.length > 0 ? userRatings.length : Number(u.rating_count || 0);

    let commText = '0% (Customer/Staff)';
    if (u.role === 'contractor') {
      if (activeSub) {
        commText = `<span style="color:var(--emerald-ok);font-weight:700;">0.00% (${activeSub.tier_code})</span>`;
      } else if (u.custom_commission_rate != null) {
        commText = `<strong style="color:var(--amber-core)">${(Number(u.custom_commission_rate) * 100).toFixed(1)}% (Manual Override)</strong>`;
      } else {
        commText = `${globalPct}% (Pay-As-You-Go)`;
      }
    }

    const accStatus = u.account_frozen ? 'SUSPENDED' : (u.account_status || 'ACTIVE');
    let stateBadge = `<span class="pill-clean">ACTIVE</span>`;
    if (accStatus === 'PENDING_REVIEW') {
      stateBadge = `<span class="pill-watch">PENDING_REVIEW</span>`;
    } else if (accStatus === 'RESUBMISSION_REQUIRED') {
      stateBadge = `<span class="pill-risk">RESUBMISSION_REQUIRED</span>`;
    } else if (accStatus === 'SUSPENDED') {
      stateBadge = `<span class="pill-risk">SUSPENDED</span><br/><small style="color:#fca5a5">${u.suspension_reason || ''}</small>`;
    }

    return `
      <tr>
        <td>
          <button type="button" class="username-link" onclick="openProfileInspector('${u.id}')" title="Click to open Full User Profile & Governance Modal">
            @${u.username || u.role}
          </button>
          ${u.vip_grind_badge ? `<div style="margin-top:0.25rem;"><span class="vip-badge">★ VIP GRIND</span></div>` : ''}
        </td>
        <td>
          <strong>${firstName} ${lastName}</strong> ${u.nickname ? `<small style="color:var(--cyan-accent)">("${u.nickname}")</small>` : ''}<br/>
          <span class="engine-tag">${u.role.toUpperCase()}</span>
        </td>
        <td>
          <code><strong>${u.phone}</strong></code><br/>
          <small style="color:var(--text-muted)">${u.barangay_zone}, ${u.municipality || 'Angeles City'} (${Number(u.home_lat || 15.145).toFixed(4)}, ${Number(u.home_lng || 120.588).toFixed(4)})</small>
        </td>
        <td>
          Wallet: <strong style="color:var(--emerald-ok)">PHP ${Number(u.credit_balance || 0).toFixed(2)}</strong><br/>
          <small>Rate: ${commText}</small>
        </td>
        <td>
          ${u.role === 'contractor'
            ? `<span class="csat-badge">★ ${avgStars} CSAT (${ratingCnt})</span> • <small>${certs.length} Cert(s)</small><br/>
               <small style="color:var(--text-muted)">Vehicle: ${cProf ? cProf.vehicle_type + ' (' + cProf.dispatch_radius_m + 'm)' : 'motorcycle (1500m)'}</small>`
            : `<span class="csat-badge">UP: ${u.thumbs_up_count ?? 5} / DOWN: ${u.thumbs_down_count ?? 0} (${Number(u.customer_trust_score ?? 100).toFixed(0)}% Trust)</span>${u.trust_deposit_required ? "<br/><span class=\"pill-risk\">DEPOSIT GATE ACTIVE</span>" : ""}${Number(u.clearing_fee_due_php || 0) > 0 ? "<br/><small style=\"color:#fca5a5\">Clearing Fee: PHP " + Number(u.clearing_fee_due_php).toFixed(2) + "</small>" : ""}`}
        </td>
        <td>${stateBadge}</td>
        <td>
          <button class="btn-xs" onclick="openProfileInspector('${u.id}')">
            Open Profile & Controls
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

function renderOperatorKycQueue() {
  const tbody = document.getElementById('operatorKycQueueTable');
  if (!tbody) return;
  const contractors = consoleState.users.filter(u => u.role === 'contractor');

  tbody.innerHTML = contractors.map(c => {
    const cProf = getContractorProfile(c.id) || {};
    const certs = Array.isArray(c.certifications_docs) ? c.certifications_docs : [];
    const verticals = [];
    if (cProf.offers_skills !== false) verticals.push('Skills Marketplace');
    if (cProf.offers_delivery) verticals.push('Delivery Rider');
    if (cProf.offers_ride_hailing) verticals.push('Ride-Hailing Rider');

    const accStatus = c.account_frozen ? 'SUSPENDED' : (c.account_status || 'ACTIVE');
    const flaggedList = Array.isArray(cProf.flagged_docs) && cProf.flagged_docs.length > 0
      ? `<div style="color:#fca5a5;font-size:0.72rem;margin-top:0.2rem;">Flagged: ${cProf.flagged_docs.join(', ')} (${cProf.resubmission_notes || ''})</div>`
      : '';

    return `
      <tr>
        <td>
          <button type="button" class="username-link" onclick="openProfileInspector('${c.id}')">@${c.username || 'contractor'}</button><br/>
          <strong>${c.full_name}</strong>
        </td>
        <td>
          <strong style="color:var(--amber-core)">Call: ${c.phone}</strong><br/>
          <small style="color:var(--text-muted)">${c.address_text || (c.barangay_zone + ', ' + c.municipality)}</small>
        </td>
        <td>
          <strong>${verticals.join(' + ')}</strong><br/>
          <small style="color:var(--cyan-accent)">Unit: ${cProf.vehicle_type || 'motorcycle'} (${cProf.dispatch_radius_m || 1500}m radius)</small>
        </td>
        <td>
          <span class="engine-tag">${certs.length} Scanned Doc(s)</span>
          ${cProf.license_doc_url ? `<span class="engine-tag">Driver License</span>` : ''}
          ${cProf.orcr_doc_url ? `<span class="engine-tag">OR/CR</span>` : ''}
          ${flaggedList}
        </td>
        <td>
          <span class="${accStatus === 'ACTIVE' ? 'pill-clean' : (accStatus === 'PENDING_REVIEW' ? 'pill-watch' : 'pill-risk')}">
            ${accStatus}
          </span>
        </td>
        <td>
          <div class="action-cluster">
            <button class="btn-xs" style="border-color:var(--emerald-ok);color:var(--emerald-ok);" onclick="operatorQuickKycDecision('${c.id}', 'ACTIVATE')">
              Approve & Activate
            </button>
            <button class="btn-xs" style="border-color:var(--amber-core);color:var(--amber-core);" onclick="promptFlagDocResubmission('${c.id}', '${c.full_name.replace(/'/g, "")}')">
              Flag Doc Resubmit
            </button>
            <button class="btn-xs" onclick="openProfileInspector('${c.id}')">
              Inspect Docs
            </button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function operatorQuickKycDecision(contractorId, decision) {
  if (!currentAuthUser) return;
  const notes = decision === 'ACTIVATE'
    ? 'Verified via manual operator voice call & compliance document inspection'
    : prompt('Enter notes:', 'Verification update');
  if (notes === null) return;

  const { data, error } = await supabaseClient.rpc('operator_review_contractor_kyc_rpc', {
    p_operator_id: currentAuthUser.id,
    p_contractor_id: contractorId,
    p_decision: decision,
    p_flagged_docs: [],
    p_notes: notes
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('KYC Review RPC Error: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
}

async function promptFlagDocResubmission(contractorId, contractorName) {
  if (!currentAuthUser) return;
  const docNames = prompt(
    `Flag blurry/incomplete document(s) for ${contractorName} (comma-separated, e.g., Driver License, OR/CR, Vehicle Photo, Trade Certificate):`,
    'Driver License, Vehicle OR/CR'
  );
  if (!docNames) return;
  const notes = prompt('Enter resubmission instructions for the contractor:', 'Please re-upload a clearer 1024px photo where license/plate numbers are legible.');
  if (notes === null) return;

  const flaggedArr = docNames.split(',').map(s => s.trim()).filter(Boolean);
  const { data, error } = await supabaseClient.rpc('operator_review_contractor_kyc_rpc', {
    p_operator_id: currentAuthUser.id,
    p_contractor_id: contractorId,
    p_decision: 'FLAG_RESUBMISSION',
    p_flagged_docs: flaggedArr,
    p_notes: notes
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Resubmission Flag Error: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
}

async function operatorOnboardUser() {
  if (!currentAuthUser) return;
  const role = document.getElementById('onbRole').value;
  const username = document.getElementById('onbUsername').value.trim();
  const firstName = document.getElementById('onbFirstName').value.trim();
  const lastName = document.getElementById('onbLastName').value.trim();
  const nickname = document.getElementById('onbNickname').value.trim() || firstName;
  const birthDate = document.getElementById('onbBirthDate').value || '1992-05-15';
  const fullName = `${firstName} ${lastName}`.trim();
  const phone = document.getElementById('onbPhone').value.trim();
  const email = document.getElementById('onbEmail').value.trim();
  const municipality = document.getElementById('onbMunicipality').value;
  const barangay = document.getElementById('onbBarangay').value;
  const addressText = document.getElementById('onbAddressText').value.trim() || `${barangay}, ${municipality}`;
  const homeLat = parseFloat(document.getElementById('onbHomeLat').value) || 15.1450;
  const homeLng = parseFloat(document.getElementById('onbHomeLng').value) || 120.5887;
  const password = document.getElementById('onbPassword').value.trim() || 'Alisto2026!';

  if (!firstName || !phone) {
    alert('First Name and Active Mobile Phone Number are required.');
    return;
  }

  if (role === 'customer') {
    const { data, error } = await supabaseClient.rpc('register_customer_portal_rpc', {
      p_full_name: fullName,
      p_nickname: nickname,
      p_phone: phone,
      p_email: email || `${firstName.toLowerCase()}@alisto.ph`,
      p_password: password,
      p_municipality: municipality,
      p_barangay_zone: barangay,
      p_address_text: addressText,
      p_home_lat: homeLat,
      p_home_lng: homeLng,
      p_profile_photo_url: null
    });
    if (error || (data && data.status === 'ERROR')) {
      alert('Customer Onboarding Error: ' + (error ? error.message : data.message));
      return;
    }
    alert(`Customer Onboarded: ${fullName} (@${data.user.username})`);
    await syncFromSupabase();
    return;
  }

  if (role === 'contractor') {
    const checkedSkills = Array.from(document.querySelectorAll('#onbSkillsCheckboxes input[type="checkbox"]:checked')).map(cb => cb.value);
    const optDelivery = document.getElementById('onbOptDelivery')?.checked || false;
    const optRide = document.getElementById('onbOptRideHailing')?.checked || false;
    const vehicleType = document.getElementById('onbVehicleType')?.value || 'motorcycle';
    const initStatus = document.getElementById('onbInitialStatus')?.value || 'PENDING_REVIEW';
    const initCredits = parseFloat(document.getElementById('onbCredits').value) || 0;
    const customCommRaw = document.getElementById('onbCustomComm').value.trim();
    const customCommDecimal = customCommRaw !== '' ? (parseFloat(customCommRaw) / 100.0) : null;

    const { data, error } = await supabaseClient.rpc('register_contractor_portal_rpc', {
      p_first_name: firstName,
      p_last_name: lastName,
      p_birth_date: birthDate,
      p_phone: phone,
      p_email: email || `${firstName.toLowerCase()}@alisto.ph`,
      p_password: password,
      p_municipality: municipality,
      p_barangay_zone: barangay,
      p_address_text: addressText,
      p_home_lat: homeLat,
      p_home_lng: homeLng,
      p_offers_skills: checkedSkills.length > 0,
      p_skill_tags: checkedSkills,
      p_offers_delivery: optDelivery,
      p_offers_ride_hailing: optRide,
      p_vehicle_type: vehicleType
    });

    if (error || (data && data.status === 'ERROR')) {
      alert('Contractor Onboarding Error: ' + (error ? error.message : data.message));
      return;
    }

    const newUid = data.user.id;
    if (initStatus === 'ACTIVE') {
      await supabaseClient.rpc('operator_review_contractor_kyc_rpc', {
        p_operator_id: currentAuthUser.id,
        p_contractor_id: newUid,
        p_decision: 'ACTIVATE',
        p_flagged_docs: [],
        p_notes: 'Verified on-site at Alisto Onboarding Clinic'
      });
    }
    if (initCredits > 0) {
      await supabaseClient.rpc('secure_topup_prepaid_credits_rpc', {
    p_session_token: getActiveSessionToken(),
        p_target_user_id: newUid,
        p_amount: initCredits,
        p_qrph_ref: 'CLINIC-INIT-' + Date.now()
      });
    }
    if (customCommDecimal !== null) {
      await supabaseClient.rpc('operator_set_contractor_commission_rpc', {
        p_operator_id: currentAuthUser.id,
        p_contractor_id: newUid,
        p_custom_rate_decimal: customCommDecimal
      });
    }

    alert(`Contractor Onboarded: ${fullName} (@${data.user.username}) [State: ${initStatus}]`);
    await syncFromSupabase();
    return;
  }

  // Admin role onboarding (SuperAdmin only)
  const { data, error } = await supabaseClient.rpc('operator_onboard_user_rpc', {
    p_operator_id: currentAuthUser.id,
    p_full_name: fullName,
    p_phone: phone,
    p_email: email,
    p_role: role,
    p_municipality: municipality,
    p_barangay_zone: barangay,
    p_password: password,
    p_kyc_verified: true,
    p_lgu_endorsed: false,
    p_skill_categories: [],
    p_sub_400cc: true,
    p_initial_credits: 0,
    p_custom_commission_rate: null,
    p_username: username || null
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Admin Onboarding Error: ' + (error ? error.message : data.message));
    return;
  }
  alert(`Admin Operator Onboarded: ${fullName} (@${data.user.username})`);
  await syncFromSupabase();
}

function renderOperatorContractorCommissionTable() {
  const tbody = document.getElementById('operatorContractorCommTable');
  const globalDisp = document.getElementById('opGlobalCommReadonlyDisplay');
  const jumpWrap = document.getElementById('opSuperAdminJumpWrap');
  if (!tbody || !currentAuthUser) return;

  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);
  if (globalDisp) globalDisp.textContent = `≤ ${globalPct}% (Default)`;

  if (jumpWrap) {
    if (currentAuthUser.role === 'super_admin') {
      jumpWrap.innerHTML = `<button class="btn-outline" onclick="switchConsoleView('superadmin')">Open SuperAdmin Pricing & Ledger Control →</button>`;
    } else {
      jumpWrap.innerHTML = `<span class="engine-tag">SUPERADMIN ONLY (READ-ONLY FOR ADMIN)</span>`;
    }
  }

  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  tbody.innerHTML = contractors.map(c => {
    const cProf = getContractorProfile(c.id) || {};
    const activeSub = getActiveSubscription(c.id);
    const hasCustom = c.custom_commission_rate !== null && c.custom_commission_rate !== undefined;
    let effPct = `${globalPct}% (Pay-As-You-Go)`;
    if (activeSub) {
      effPct = `0.00% (${activeSub.tier_code})`;
    } else if (hasCustom) {
      effPct = `${(Number(c.custom_commission_rate) * 100).toFixed(1)}% (Manual Rate)`;
    }

    return `
      <tr>
        <td>
          <button type="button" class="username-link" onclick="openProfileInspector('${c.id}')">@${c.username || 'contractor'}</button>
          ${c.vip_grind_badge ? `<span class="vip-badge">VIP</span>` : ''}<br/>
          <small>${c.full_name}</small>
        </td>
        <td>
          <strong style="color:var(--emerald-ok)">PHP ${Number(c.credit_balance || 0).toFixed(2)}</strong><br/>
          <small style="color:var(--cyan-accent)">${activeSub ? activeSub.tier_code : 'STANDARD_PAYG'}</small>
        </td>
        <td>
          <span class="engine-tag">${cProf.vehicle_type || 'motorcycle'} (${cProf.dispatch_radius_m || 1500}m)</span><br/><small style="color:var(--cyan-accent)">Shift: ${(cProf.active_shift_verticals || ['skills_marketplace']).join(', ')}</small>
        </td>
        <td><span class="engine-tag">${effPct}</span></td>
        <td>
          <div class="action-cluster">
            <button class="btn-xs" onclick="promptContractorCommissionOverride('${c.id}', '${c.full_name.replace(/'/g, "")}')">
              Set Manual %
            </button>
            <button class="btn-xs" onclick="openProfileInspector('${c.id}')">
              Pass / Profile
            </button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function promptContractorCommissionOverride(contractorId, contractorName) {
  if (!currentAuthUser) return;
  const input = prompt(
    `Set Manual Commission Rate (%) for ${contractorName} (Capped <= 12%, e.g., enter 8 for 8%, or leave blank for Global Default):`,
    ''
  );
  if (input === null) return;
  const trimmed = input.trim();
  const decimalVal = trimmed === '' ? null : (parseFloat(trimmed) / 100.0);

  if (decimalVal !== null && (isNaN(decimalVal) || decimalVal < 0 || decimalVal > 0.12)) {
    alert('Per V3 Spec, commission rates are capped at <= 12.0%. Enter a value between 0 and 12.');
    return;
  }

  const { error, data } = await supabaseClient.rpc('operator_set_contractor_commission_rpc', {
    p_operator_id: currentAuthUser.id,
    p_contractor_id: contractorId,
    p_custom_rate_decimal: decimalVal
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Error updating manual commission rate: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
}

// ============================================================================
// 5. UNIFIED USER PROFILE & GOVERNANCE COMMAND MODAL (CLICKING @USERNAME)
// ============================================================================

function openProfileInspector(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  activeProfileModalUserId = u.id;

  const { firstName, lastName } = splitFirstLastName(u);
  const cProf = getContractorProfile(u.id) || {};
  const activeSub = getActiveSubscription(u.id);
  const actorIsSuper = currentAuthUser.role === 'super_admin';
  const isTargetSuper = u.role === 'super_admin';
  const isTargetAdmin = u.role === 'admin';
  const canEditTarget = actorIsSuper || !isTargetSuper;
  const canSuspendTarget = !isTargetSuper && (actorIsSuper || !isTargetAdmin);

  const userLedger = consoleState.ledger.filter(l => l.user_id === u.id);
  const userRatings = consoleState.ratings.filter(r => r.ratee_id === u.id);
  const certs = Array.isArray(u.certifications_docs) ? u.certifications_docs : [];

  const avgStars = userRatings.length > 0
    ? (userRatings.reduce((acc, r) => acc + Number(r.stars || 5), 0) / userRatings.length).toFixed(2)
    : Number(u.rating_avg || 5.0).toFixed(2);
  const ratingCnt = userRatings.length > 0 ? userRatings.length : Number(u.rating_count || 0);

  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);
  const manualCommVal = u.custom_commission_rate != null ? (Number(u.custom_commission_rate) * 100).toFixed(1) : '';
  const accStatus = u.account_frozen ? 'SUSPENDED' : (u.account_status || 'ACTIVE');

  document.getElementById('profileModalTitle').innerHTML =
    `User Profile Command • <span style="color:var(--cyan-accent)">@${u.username || u.role}</span> — ${u.full_name} [${accStatus}]`;

  // Scanned Certificates & Compliance Docs Gallery (1024px compressed)
  const certsHtml = certs.length === 0
    ? `<div style="color:var(--text-muted);padding:0.4rem 0;">No scanned certificates, licenses, or OR/CR attached yet.</div>`
    : `<div class="cert-grid">` + certs.map((c, idx) => `
        <div class="cert-card">
          <img src="${c.image_data_url}" alt="${c.title}" onclick="previewCertImage('${u.id}', ${idx})" title="Click to inspect full 1024px compliance scan" />
          <div style="font-weight:700;font-size:0.78rem;color:var(--cyan-accent);">${c.title || 'Compliance Document'}</div>
          <div style="font-size:0.72rem;color:var(--text-muted);">
            Ref/License #: <code>${c.license_no || 'N/A'}</code><br/>
            Authority: ${c.issued_by || 'LGU / LTO / TESDA'} • ${c.uploaded_at || ''}
          </div>
          ${canEditTarget ? `<button class="btn-danger" style="margin-top:auto;" onclick="removeUserCertification('${u.id}', ${idx})">Remove File</button>` : ''}
        </div>
      `).join('') + `</div>`;

  // Subscription Pass Activation Panel (For Contractors)
  const subscriptionPanelHtml = u.role === 'contractor' ? `
    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;margin-bottom:0.6rem;">
        <h4>4. Hybrid Monetization & Subscription Pass Control (Section 5 of V3 Spec)</h4>
        <div>
          ${activeSub
            ? `<span class="pill-clean">ACTIVE PASS: ${activeSub.tier_code} (Expires: ${formatTs(activeSub.expires_at)})</span>`
            : `<span class="engine-tag">CURRENT TIER: STANDARD PAY-AS-YOU-GO (≤${globalPct}%)</span>`}
          ${u.vip_grind_badge ? `<span class="vip-badge" style="margin-left:0.4rem;">★ VIP GRIND BADGE (Priority Weight: ${u.priority_dispatch_weight})</span>` : ''}
        </div>
      </div>
      <div class="profile-input-grid" style="grid-template-columns: 1fr auto; align-items: end;">
        <div>
          <label>Select Monthly Subscription Pass to Activate (Unlocks 0.00% Commission Window & Bundled Bonus Cushion)</label>
          <select id="profSubPassSelect">
            <option value="PART_TIME_PASS">Part-Time Pass — ₱3,000.00 (4h/day @ 0% Comm + ₱150 Overtime Cushion, 30 Days)</option>
            <option value="FULL_TIME_PASS">Full-Time Pass — ₱5,000.00 (8h/day @ 0% Comm + ₱250 Overtime Cushion, 32 Days Total)</option>
            <option value="GRIND_PASS">Hard-Working (Grind) Pass — ₱8,000.00 (24/7 Unlimited 0% Comm + Priority Dispatch + VIP Badge, 33 Days Total)</option>
          </select>
        </div>
        <div>
          <button class="btn-primary" onclick="activateContractorSubscriptionPass('${u.id}')">
            Activate Subscription Pass
          </button>
        </div>
      </div>
    </div>
  ` : '';

  // Contractor CSAT Section HTML
  const csatSectionHtml = u.role === 'contractor' ? `
    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;margin-bottom:0.6rem;">
        <h4>5. Contractor CSAT Scorecard & Verification Pipeline (Engine 01 & Engine 07)</h4>
        <div class="action-cluster">
          <span class="csat-badge">★ ${avgStars} / 5.00 CSAT (${ratingCnt} Reviews)</span>
          <button class="btn-xs" style="border-color:var(--emerald-ok);color:var(--emerald-ok);" onclick="operatorQuickKycDecision('${u.id}', 'ACTIVATE')">
            Approve & Set ACTIVE
          </button>
          <button class="btn-xs" style="border-color:var(--amber-core);color:var(--amber-core);" onclick="promptFlagDocResubmission('${u.id}', '${u.full_name.replace(/'/g, "")}')">
            Flag Blurry Doc for Resubmission
          </button>
          <button class="btn-xs" onclick="promptAddTestCsatReview('${u.id}')">+ Log CSAT Review</button>
        </div>
      </div>
      <div style="max-height:160px;overflow-y:auto;">
        ${userRatings.length === 0
          ? `<div style="color:var(--text-muted)">No itemized CSAT reviews recorded yet.</div>`
          : userRatings.map(r => `
              <div style="padding:0.45rem;margin-bottom:0.4rem;background:#090d16;border:1px solid var(--border-subtle);border-radius:6px;">
                <div style="display:flex;justify-content:space-between;">
                  <strong style="color:var(--amber-core)">${'★'.repeat(r.stars)}${'☆'.repeat(5 - r.stars)} (${r.stars}/5)</strong>
                  <span style="font-size:0.72rem;color:var(--text-muted)">Reviewer: ${getUserLabel(r.rater_id)} • ${formatTs(r.created_at)}</span>
                </div>
                <div style="color:var(--text-main);font-size:0.78rem;margin-top:0.2rem;">"${r.comments || 'Verified satisfactory service completion.'}"</div>
              </div>
            `).join('')}
      </div>
    </div>
  ` : '';

  const ledgerRows = userLedger.length === 0
    ? `<div style="color:var(--text-muted)">No wallet ledger entries recorded for this account.</div>`
    : userLedger.map(l => `
        <div style="padding:0.35rem 0;border-bottom:1px solid var(--border-subtle);font-size:0.75rem;">
          <strong>${formatTs(l.created_at)}</strong> • <code>${l.entry_type}</code> •
          <strong style="color:${Number(l.amount) >= 0 ? 'var(--emerald-ok)' : '#fca5a5'}">
            ${Number(l.amount) >= 0 ? '+' : ''}PHP ${Number(l.amount).toFixed(2)}
          </strong>
          (Balance After: PHP ${Number(l.balance_after).toFixed(2)}) — ${l.notes || ''}
        </div>
      `).join('');

  const body = document.getElementById('profileModalBody');
  body.innerHTML = `
    <div class="forensic-grid">
      <!-- PANEL 1: EDIT PROFILE DETAILS, NICKNAME, GPS HOME PIN & SKILLS -->
      <div class="forensic-box">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.5rem;">
          <h4>1. Profile, Phone, GPS Home Anchor & Skills (@${u.username || u.role})</h4>
          ${u.profile_photo_url ? `<img src="${u.profile_photo_url}" style="width:44px;height:44px;border-radius:50%;object-fit:cover;border:2px solid var(--cyan-accent);" title="Compressed VGA Profile Photo" />` : ''}
        </div>
        <div class="profile-input-grid">
          <div>
            <label>Username (@username)</label>
            <input type="text" id="profUsername" value="${u.username || ''}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Preferred Nickname</label>
            <input type="text" id="profNickname" value="${u.nickname || firstName}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>First Name</label>
            <input type="text" id="profFirstName" value="${firstName}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Last Name</label>
            <input type="text" id="profLastName" value="${lastName}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Active Mobile Phone (Direct Call)</label>
            <input type="text" id="profPhone" value="${u.phone || ''}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Email Address</label>
            <input type="email" id="profEmail" value="${u.email || ''}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Municipality & Barangay</label>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.3rem;">
              <input type="text" id="profMunicipality" value="${u.municipality || 'Angeles City'}" ${!canEditTarget ? 'disabled' : ''} />
              <input type="text" id="profBarangay" value="${u.barangay_zone || 'Malabanias'}" ${!canEditTarget ? 'disabled' : ''} />
            </div>
          </div>
          <div>
            <label>GPS Home Pin (Lat / Lng)</label>
            <div style="display:grid;grid-template-columns:1fr 1fr;gap:0.3rem;">
              <input type="number" id="profHomeLat" value="${u.home_lat || 15.1450}" step="0.0001" ${!canEditTarget ? 'disabled' : ''} />
              <input type="number" id="profHomeLng" value="${u.home_lng || 120.5887}" step="0.0001" ${!canEditTarget ? 'disabled' : ''} />
            </div>
          </div>
          <div>
            <label>Barangay KYC Verified</label>
            <select id="profKyc" ${!canEditTarget ? 'disabled' : ''}>
              <option value="true" ${u.kyc_verified ? 'selected' : ''}>KYC Verified (YES)</option>
              <option value="false" ${!u.kyc_verified ? 'selected' : ''}>Unverified (NO)</option>
            </select>
          </div>
          <div>
            <label>LGU / PESO Endorsement</label>
            <select id="profLgu" ${!canEditTarget ? 'disabled' : ''}>
              <option value="true" ${u.lgu_peso_endorsed ? 'selected' : ''}>LGU/PESO Endorsed (YES)</option>
              <option value="false" ${!u.lgu_peso_endorsed ? 'selected' : ''}>Not Endorsed (NO)</option>
            </select>
          </div>
        </div>
        <div style="margin-top:0.5rem;">
          <label style="display:block;font-size:0.74rem;color:var(--text-muted);margin-bottom:0.2rem;">
            Skills & Trade Categories (Comma-Separated)
          </label>
          <input type="text" id="profSkills" value="${(u.skill_categories || []).join(', ')}"
                 style="width:100%;padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);"
                 ${!canEditTarget ? 'disabled' : ''} />
        </div>
        ${canEditTarget
          ? `<button class="btn-primary" style="margin-top:0.65rem;width:100%;" onclick="saveProfileModalChanges('${u.id}')">
               Save Profile, Phone, GPS Anchor & Skills Updates
             </button>`
          : ''}
      </div>

      <!-- PANEL 2: PASSWORD RESET, MANUAL SUSPENSION & MANUAL COMMISSION RATE -->
      <div class="forensic-box">
        <h4>2. Security, Manual Suspension & User Commission Rate</h4>

        <!-- Permanent Non-Expiring Password Reset -->
        <div style="padding-bottom:0.65rem;margin-bottom:0.65rem;border-bottom:1px solid var(--border-subtle);">
          <label style="display:block;font-size:0.75rem;color:var(--text-muted);margin-bottom:0.25rem;">
            Reset Permanent Password (public.user_credentials)
          </label>
          <div style="display:grid;grid-template-columns:1fr auto;gap:0.45rem;">
            <input type="text" id="profNewPasswordInput" placeholder="Enter new permanent password"
                   style="padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);"
                   ${!canEditTarget ? 'disabled' : ''} />
            <button class="btn-primary" onclick="saveProfilePasswordReset('${u.id}')" ${!canEditTarget ? 'disabled' : ''}>
              Reset Password
            </button>
          </div>
        </div>

        <!-- Manual Suspension & Lift Suspension -->
        <div style="padding-bottom:0.65rem;margin-bottom:0.65rem;border-bottom:1px solid var(--border-subtle);">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.3rem;">
            <span style="font-size:0.76rem;color:var(--text-muted);">Account State (account_status):</span>
            <span class="${accStatus === 'ACTIVE' ? 'pill-clean' : 'pill-risk'}">${accStatus}</span>
          </div>
          <div style="display:grid;grid-template-columns:1fr auto;gap:0.45rem;">
            <input type="text" id="profSuspendReasonInput"
                   value="${u.suspension_reason || ''}"
                   placeholder="Suspension / reinstatement audit note..."
                   style="padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);"
                   ${!canSuspendTarget ? 'disabled' : ''} />
            ${u.account_frozen
              ? `<button class="btn-primary" style="background:var(--emerald-ok);" onclick="saveProfileSuspensionToggle('${u.id}', false)" ${!canSuspendTarget ? 'disabled' : ''}>
                   Lift Suspension Manually
                 </button>
            <button class="btn-outline" style="border-color:var(--crimson-err);color:#fca5a5;" onclick="revokeUserSessions('${u.id}', '${u.username || u.full_name}')">Revoke Active Sessions</button>`
              : `<button class="btn-danger" onclick="saveProfileSuspensionToggle('${u.id}', true)" ${!canSuspendTarget ? 'disabled' : ''}>
                   Suspend User Manually
                 </button>`}
          </div>
        </div>

        <!-- Manual Commission Rate of Specific User (SuperAdmin & Admin, Capped <= 12%) -->
        <div>
          <label style="display:block;font-size:0.75rem;color:var(--text-muted);margin-bottom:0.25rem;">
            Manual Commission Rate for This User (% — Capped ≤ 12.0%, Global Default is ${globalPct}%)
          </label>
          <div style="display:grid;grid-template-columns:1fr auto auto;gap:0.45rem;">
            <input type="number" id="profManualCommInput" value="${manualCommVal}"
                   placeholder="Default (${globalPct}%)" min="0" max="12" step="0.5"
                   style="padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);" />
            <button class="btn-primary" onclick="saveProfileManualCommission('${u.id}', false)">
              Set User Rate
            </button>
            <button class="btn-outline" onclick="saveProfileManualCommission('${u.id}', true)">
              Use Default
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- PANEL 3: BANDWIDTH-OPTIMIZED MEDIA & COMPLIANCE ASSET VAULT (SECTION 4.2) -->
    <div class="forensic-box">
      <h4>3. Bandwidth-Optimized Asset Upload Vault — VGA Profile Photo (<80 KB) & 1024px Compliance Docs (${certs.length} Attached)</h4>
      <div class="profile-input-grid" style="grid-template-columns: 1fr 1.1fr 1fr 1fr 1.1fr auto; align-items: end;">
        <div>
          <label>Compression Profile</label>
          <select id="certCompressionSelect">
            <option value="COMPLIANCE_DOC">Compliance Doc (1024px • 65% JPEG)</option>
            <option value="PROFILE_VGA">Profile Photo (VGA 640x480 • <80KB)</option>
          </select>
        </div>
        <div>
          <label>Document / Asset Type</label>
          <input type="text" id="certTitleInput" placeholder="e.g., Driver License / OR-CR / TESDA NC-II" />
        </div>
        <div>
          <label>License / Plate / Cert #</label>
          <input type="text" id="certNumberInput" placeholder="e.g., N03-26-009182" />
        </div>
        <div>
          <label>Issuing Authority</label>
          <input type="text" id="certIssuerInput" placeholder="e.g., LTO / TESDA / PESO" />
        </div>
        <div>
          <label>Select Image File</label>
          <input type="file" id="certFileInput" accept="image/*" />
        </div>
        <div>
          <button class="btn-primary" onclick="uploadUserCertificationImage('${u.id}')" ${!canEditTarget ? 'disabled' : ''}>
            + Compress & Attach
          </button>
        </div>
      </div>
      ${certsHtml}
    </div>

    <!-- PANEL 4: SUBSCRIPTION PASS CONTROL -->
    ${subscriptionPanelHtml}

    <!-- PANEL 5: CONTRACTOR CSAT & KYC VERIFICATION -->
    ${csatSectionHtml}

    <!-- PANEL 6: WALLET CREDIT / DEBIT & PAYMONGO QR PH TOP-UP (+P25 BONUS) -->
    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;margin-bottom:0.65rem;">
        <h4>6. Prepaid Wallet Credit / Debit & PayMongo QR Ph Top-Up (public.credit_ledger)</h4>
        <div>
          <span style="font-size:0.85rem;margin-right:0.8rem;">
            Available Balance: <strong style="color:var(--emerald-ok);font-size:1.05rem;">PHP ${Number(u.credit_balance || 0).toFixed(2)}</strong>
          </span>
          <span style="font-size:0.82rem;color:var(--amber-core);">
            Locked Commission Holds: <strong>PHP ${Number(u.held_credits || 0).toFixed(2)}</strong>
          </span>
        </div>
      </div>

      <div class="profile-input-grid" style="grid-template-columns: 220px 150px 1fr auto; align-items: end; margin-bottom: 0.75rem;">
        <div>
          <label>Wallet Operation</label>
          <select id="walletAdjDirection">
            <option value="TOPUP_QRPH">PayMongo QR Ph Top-Up (≥₱500 gets +₱25 Bonus!)</option>
            <option value="CREDIT">Operator Manual CREDIT (+ Add)</option>
            <option value="DEBIT">Operator Manual DEBIT (- Deduct)</option>
          </select>
        </div>
        <div>
          <label>Amount (PHP)</label>
          <input type="number" id="walletAdjAmount" value="500" min="1" step="50" />
        </div>
        <div>
          <label>Audit Reference / Justification Note</label>
          <input type="text" id="walletAdjReason" placeholder="e.g., Cash-in ≥₱500 with automatic +₱25 bonus grant" />
        </div>
        <div>
          <button class="btn-primary" onclick="executeProfileWalletAdjustment('${u.id}')">
            Execute Wallet Transaction
          </button>
        </div>
      </div>

      <div style="max-height:175px;overflow-y:auto;background:#090d16;padding:0.6rem;border-radius:6px;border:1px solid var(--border-subtle);">
        ${ledgerRows}
      </div>
    </div>
  `;

  document.getElementById('profileModal').style.display = 'flex';
}

function closeProfileModal() {
  activeProfileModalUserId = null;
  document.getElementById('profileModal').style.display = 'none';
}

async function saveProfileModalChanges(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;

  const username = document.getElementById('profUsername').value.trim();
  const nickname = document.getElementById('profNickname').value.trim();
  const firstName = document.getElementById('profFirstName').value.trim();
  const lastName = document.getElementById('profLastName').value.trim();
  const fullName = `${firstName} ${lastName}`.trim() || u.full_name;
  const phone = document.getElementById('profPhone').value.trim();
  const email = document.getElementById('profEmail').value.trim();
  const municipality = document.getElementById('profMunicipality').value.trim();
  const barangay = document.getElementById('profBarangay').value.trim();
  const kyc = document.getElementById('profKyc').value === 'true';
  const lgu = document.getElementById('profLgu').value === 'true';
  const skillsRaw = document.getElementById('profSkills').value;
  const skillsArr = skillsRaw.split(',').map(s => s.trim()).filter(Boolean);

  const { data, error } = await supabaseClient.rpc('operator_update_user_profile_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_username: username,
    p_first_name: firstName,
    p_last_name: lastName,
    p_full_name: fullName,
    p_phone: phone,
    p_email: email,
    p_municipality: municipality,
    p_barangay_zone: barangay,
    p_kyc_verified: kyc,
    p_lgu_endorsed: lgu,
    p_skill_categories: skillsArr,
    p_certifications_docs: Array.isArray(u.certifications_docs) ? u.certifications_docs : []
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Profile Update Error: ' + (error ? error.message : data.message));
    return;
  }

  alert(`Profile details, phone & skills updated for @${username} (${ nickname || fullName }).`);
  await syncFromSupabase();
}

async function activateContractorSubscriptionPass(contractorId) {
  if (!currentAuthUser) return;
  const tierCode = document.getElementById('profSubPassSelect').value;
  const { data, error } = await supabaseClient.rpc('secure_purchase_subscription_pass_rpc', {
    p_session_token: getActiveSessionToken(),
    p_contractor_id: contractorId,
    p_tier_code: tierCode,
    p_payment_ref: 'PASS-OP-' + Date.now()
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Subscription Pass Activation Error: ' + (error ? error.message : data.message));
    return;
  }
  alert(`Subscription Pass [${tierCode}] activated! 0.00% commission window and bonus cushion applied.`);
  await syncFromSupabase();
}

async function saveProfilePasswordReset(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  const newPw = document.getElementById('profNewPasswordInput').value.trim();
  if (!newPw) {
    alert('Enter the new password first.');
    return;
  }

  const { data, error } = await supabaseClient.rpc('operator_reset_user_password_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_new_password: newPw
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Password Reset Error: ' + (error ? error.message : data.message));
    return;
  }

  document.getElementById('profNewPasswordInput').value = '';
  alert(`Permanent password updated for @${u.username || u.full_name}.`);
  await syncFromSupabase();
}

async function saveProfileSuspensionToggle(userId, suspend) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  const reasonInput = document.getElementById('profSuspendReasonInput').value.trim();
  const reason = reasonInput || (suspend ? 'Platform rule violation' : 'Suspension lifted manually by operator');

  const { data, error } = await supabaseClient.rpc('operator_set_account_suspension_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_suspend: suspend,
    p_reason: reason
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Suspension Error: ' + (error ? error.message : data.message));
    return;
  }

  await syncFromSupabase();
}

async function saveProfileManualCommission(userId, clearToDefault) {
  if (!currentAuthUser) return;
  let decimalVal = null;
  if (!clearToDefault) {
    const raw = document.getElementById('profManualCommInput').value.trim();
    if (raw !== '') {
      const pct = parseFloat(raw);
      if (isNaN(pct) || pct < 0 || pct > 12) {
        alert('Per V3 Spec, commission rates are capped at <= 12.0%.');
        return;
      }
      decimalVal = +(pct / 100.0).toFixed(4);
    }
  }

  const { data, error } = await supabaseClient.rpc('operator_set_contractor_commission_rpc', {
    p_operator_id: currentAuthUser.id,
    p_contractor_id: userId,
    p_custom_rate_decimal: decimalVal
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Manual Commission Rate Error: ' + (error ? error.message : data.message));
    return;
  }

  alert(decimalVal === null
    ? 'User commission rate restored to Global Default.'
    : `Manual user commission rate set to ${(decimalVal * 100).toFixed(1)}%.`);
  await syncFromSupabase();
}

async function executeProfileWalletAdjustment(userId) {
  if (!currentAuthUser) return;
  const direction = document.getElementById('walletAdjDirection').value;
  const amount = parseFloat(document.getElementById('walletAdjAmount').value);
  const reason = document.getElementById('walletAdjReason').value.trim() || `Manual ${direction} via Profile Command`;

  if (isNaN(amount) || amount <= 0) {
    alert('Enter a valid amount greater than PHP 0.00.');
    return;
  }

  if (direction === 'TOPUP_QRPH') {
    const { data, error } = await supabaseClient.rpc('secure_topup_prepaid_credits_rpc', {
    p_session_token: getActiveSessionToken(),
      p_target_user_id: userId,
      p_amount: amount,
      p_qrph_ref: 'QRPH-' + Date.now()
    });
    if (error || (data && data.status === 'ERROR')) {
      alert('Top-Up Error: ' + (error ? error.message : data.message));
      return;
    }
    if (data && Number(data.bonus_granted) > 0) {
      alert(`Top-Up Complete! Paid: PHP ${amount.toFixed(2)} + Instant Promo Bonus: +PHP ${Number(data.bonus_granted).toFixed(2)}!`);
    }
    await syncFromSupabase();
    return;
  }

  const { data, error } = await supabaseClient.rpc('operator_adjust_user_wallet_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_direction: direction,
    p_amount: amount,
    p_reason: reason
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Wallet Adjustment Error: ' + (error ? error.message : data.message));
    return;
  }

  document.getElementById('walletAdjReason').value = '';
  await syncFromSupabase();
}

async function uploadUserCertificationImage(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;

  const compMode = document.getElementById('certCompressionSelect').value;
  const title = document.getElementById('certTitleInput').value.trim() || (compMode === 'PROFILE_VGA' ? 'Profile Photo (VGA 640x480)' : 'Compliance Document (1024px)');
  const licNo = document.getElementById('certNumberInput').value.trim() || 'VERIFIED-ASSET';
  const issuer = document.getElementById('certIssuerInput').value.trim() || 'LGU / LTO / TESDA';
  const fileInput = document.getElementById('certFileInput');

  let imageDataUrl = '';
  let specLabel = compMode === 'PROFILE_VGA' ? 'VGA 640x480 (<80KB)' : '1024px Low/Med Res';

  if (fileInput && fileInput.files && fileInput.files[0]) {
    const compressed = await compressMediaBySpec(fileInput.files[0], compMode);
    imageDataUrl = compressed.dataUrl;
    specLabel = `${compressed.width}x${compressed.height}px (~${compressed.approxKb} KB)`;
  } else {
    imageDataUrl = `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300" viewBox="0 0 480 300"><rect width="480" height="300" fill="%230f172a" stroke="%2334d399" stroke-width="6"/><text x="240" y="60" fill="%23fbbf24" font-family="sans-serif" font-size="17" font-weight="bold" text-anchor="middle">${encodeURIComponent(issuer.toUpperCase())} • ${encodeURIComponent(specLabel)}</text><text x="240" y="115" fill="%23ffffff" font-family="sans-serif" font-size="18" font-weight="bold" text-anchor="middle">${encodeURIComponent(title)}</text><text x="240" y="160" fill="%2300f2fe" font-family="sans-serif" font-size="15" text-anchor="middle">Holder: ${encodeURIComponent(u.full_name)} (@${encodeURIComponent(u.username || '')})</text><text x="240" y="205" fill="%2334d399" font-family="sans-serif" font-size="14" font-weight="bold" text-anchor="middle">Ref / License #: ${encodeURIComponent(licNo)}</text></svg>`;
  }

  const existingCerts = Array.isArray(u.certifications_docs) ? [...u.certifications_docs] : [];
  existingCerts.unshift({
    id: 'cert_' + Date.now(),
    title: `${title} [${specLabel}]`,
    license_no: licNo,
    issued_by: issuer,
    uploaded_at: new Date().toLocaleDateString() + ' ' + new Date().toLocaleTimeString(),
    image_data_url: imageDataUrl
  });

  const { firstName, lastName } = splitFirstLastName(u);
  const { data, error } = await supabaseClient.rpc('operator_update_user_profile_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_username: u.username,
    p_first_name: firstName,
    p_last_name: lastName,
    p_full_name: u.full_name,
    p_phone: u.phone,
    p_email: u.email,
    p_municipality: u.municipality,
    p_barangay_zone: u.barangay_zone,
    p_kyc_verified: u.kyc_verified,
    p_lgu_endorsed: u.lgu_peso_endorsed,
    p_skill_categories: u.skill_categories || [],
    p_certifications_docs: existingCerts
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Asset Upload Error: ' + (error ? error.message : data.message));
    return;
  }

  await syncFromSupabase();
}

async function removeUserCertification(userId, certIndex) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  const existingCerts = Array.isArray(u.certifications_docs) ? [...u.certifications_docs] : [];
  existingCerts.splice(certIndex, 1);

  const { firstName, lastName } = splitFirstLastName(u);
  await supabaseClient.rpc('operator_update_user_profile_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_username: u.username,
    p_first_name: firstName,
    p_last_name: lastName,
    p_full_name: u.full_name,
    p_phone: u.phone,
    p_email: u.email,
    p_municipality: u.municipality,
    p_barangay_zone: u.barangay_zone,
    p_kyc_verified: u.kyc_verified,
    p_lgu_endorsed: u.lgu_peso_endorsed,
    p_skill_categories: u.skill_categories || [],
    p_certifications_docs: existingCerts
  });
  await syncFromSupabase();
}

function previewCertImage(userId, certIndex) {
  const u = getUserById(userId);
  if (!u || !Array.isArray(u.certifications_docs) || !u.certifications_docs[certIndex]) return;
  const c = u.certifications_docs[certIndex];
  const w = window.open('', '_blank', 'width=760,height=560');
  if (w) {
    w.document.write(`<html><head><title>${c.title}</title></head><body style="margin:0;background:#070a12;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;padding:20px;"><h3 style="color:#00f2fe;margin-bottom:8px;">${c.title} (${c.license_no})</h3><p style="color:#94a3b8;margin-top:0;">Authority: ${c.issued_by} • Holder: ${u.full_name} (@${u.username})</p><img src="${c.image_data_url}" style="max-width:95%;max-height:75vh;border:2px solid #00f2fe;border-radius:8px;" /></body></html>`);
  }
}

async function promptAddTestCsatReview(contractorId) {
  const starsStr = prompt('Enter CSAT Star Rating (1 to 5):', '5');
  if (!starsStr) return;
  const stars = Math.min(5, Math.max(1, parseInt(starsStr, 10) || 5));
  const comments = prompt('Enter Client CSAT Feedback Comment:', 'Excellent workmanship, arrived promptly and completed service cleanly.') || '';
  const firstBooking = consoleState.bookings[0];
  if (!firstBooking) {
    alert('Create at least one booking first to attach a CSAT rating.');
    return;
  }

  const { error } = await supabaseClient.rpc('operator_record_csat_review_rpc', {
    p_booking_id: firstBooking.id,
    p_rater_id: firstBooking.client_id,
    p_ratee_id: contractorId,
    p_stars: stars,
    p_tags: ['Verified Service', 'LGU Endorsed Quality'],
    p_comments: comments
  });
  if (error) {
    alert('CSAT RPC Error: ' + error.message);
    return;
  }
  await syncFromSupabase();
}

// ============================================================================
// 6. BOOKINGS TABLE & FORENSIC INVESTIGATION DOSSIER
// ============================================================================

function renderOperatorBookingsTable() {
  const tbody = document.getElementById('operatorBookingsTable');
  if (!tbody) return;
  const q = (document.getElementById('bookingSearchInput')?.value || '').toLowerCase().trim();
  const statusFilter = document.getElementById('bookingStatusFilter')?.value || 'ALL';
  const vertFilter = document.getElementById('bookingVerticalFilter')?.value || 'ALL';

  const filtered = consoleState.bookings.filter(b => {
    if (vertFilter !== 'ALL' && b.vertical_key !== vertFilter) return false;
    if (statusFilter === 'SEARCHING' && b.status !== 'SEARCHING') return false;
    if (statusFilter === 'ACTIVE' && !['ACCEPTED', 'ARRIVED', 'IN_PROGRESS'].includes(b.status)) return false;
    if (statusFilter === 'COMPLETED' && b.status !== 'COMPLETED') return false;
    if (statusFilter === 'CANCELLED' && !['CANCELLED', 'EXPIRED'].includes(b.status)) return false;

    if (!q) return true;
    const clientName = getUserLabel(b.client_id);
    const contName = getUserLabel(b.contractor_id);
    const hay = `${b.booking_ref} ${b.service_title} ${b.barangay_zone} ${b.municipality} ${clientName} ${contName} ${b.status}`.toLowerCase();
    return hay.includes(q);
  });

  tbody.innerHTML = filtered.map(b => {
    const msgCount = consoleState.messages.filter(m => m.booking_id === b.id).length;
    const flagCount = consoleState.moderationAuditLogs.filter(l => l.booking_id === b.id).length;
    const gpsCount = consoleState.telemetry.filter(t => t.booking_id === b.id).length;
    const clientObj = getUserById(b.client_id);
    const contObj = getUserById(b.contractor_id);

    return `
      <tr>
        <td>
          <code><strong>${b.booking_ref}</strong></code><br/>
          <small style="color:var(--cyan-accent)">${b.vertical_key}</small>
        </td>
        <td>
          <strong>${b.service_title}</strong><br/>
          <small style="color:var(--text-muted)">${b.barangay_zone}, ${b.municipality || 'Angeles City'} • ${b.pickup_address}</small>
        </td>
        <td>
          <small><strong>Client:</strong> ${clientObj ? `<button type="button" class="username-link" onclick="openProfileInspector('${clientObj.id}')">@${clientObj.username}</button> ${clientObj.full_name}` : b.client_id}</small><br/>
          <small><strong>Contractor:</strong> ${contObj ? `<button type="button" class="username-link" onclick="openProfileInspector('${contObj.id}')">@${contObj.username}</button> ${contObj.full_name}` : 'Unclaimed (On Board)'}</small>
        </td>
        <td>
          Fee: <strong>PHP ${Number(b.quoted_fee).toFixed(2)}</strong><br/>
          <small>Tip (100% Contractor): PHP ${Number(b.tip_amount || 0).toFixed(2)} | Hold: PHP ${Number(b.commission_locked || 0).toFixed(2)}</small>
        </td>
        <td style="font-size:0.74rem;line-height:1.45;">
          <strong>Req:</strong> ${formatTs(b.created_at)}<br/>
          ${b.accepted_at ? `<strong>Acc:</strong> ${formatTs(b.accepted_at)}<br/>` : ''}
          ${b.arrived_at ? `<strong>Arr:</strong> ${formatTs(b.arrived_at)}<br/>` : ''}
          ${b.completed_at ? `<strong>Cmp:</strong> ${formatTs(b.completed_at)}` : ''}
        </td>
        <td><span class="engine-tag">${b.status}</span></td>
        <td>
          <button class="btn-primary" style="padding:0.4rem 0.7rem;font-size:0.76rem;" onclick="openForensicDossier('${b.id}')">
            Forensic Dossier (${msgCount} Chat / ${flagCount} Flags / ${gpsCount} GPS)
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

function openForensicDossier(bookingId) {
  const b = consoleState.bookings.find(x => x.id === bookingId);
  if (!b) return;

  const client = getUserById(b.client_id);
  const contractor = getUserById(b.contractor_id);
  const msgs = consoleState.messages.filter(m => m.booking_id === b.id);
  const flags = consoleState.moderationAuditLogs.filter(l => l.booking_id === b.id);
  const gps = consoleState.telemetry.filter(t => t.booking_id === b.id);

  document.getElementById('forensicModalTitle').textContent =
    `Forensic Investigation Dossier • ${b.booking_ref} [${b.status}]`;

  const chatHtml = msgs.length === 0
    ? `<div style="color:var(--text-muted)">No delivered messages recorded for this booking.</div>`
    : msgs.map(m => `
        <div style="padding:0.4rem 0;border-bottom:1px solid var(--border-subtle);">
          <strong style="color:var(--cyan-accent)">${getUserLabel(m.sender_id)}</strong>
          <span style="float:right;color:var(--text-muted);font-size:0.72rem;">${formatTs(m.sent_at)}</span><br/>
          <span>${m.message_body}</span>
        </div>
      `).join('');

  const flagsHtml = flags.length === 0
    ? `<div style="color:var(--emerald-ok)">Zero blocked moderation infractions on this booking.</div>`
    : flags.map(f => `
        <div style="padding:0.45rem;margin-bottom:0.4rem;background:rgba(239,68,68,0.15);border:1px solid var(--crimson-err);border-radius:6px;">
          <strong style="color:#fca5a5">${getUserLabel(f.sender_id)}</strong>
          <span style="float:right;font-size:0.72rem;">${formatTs(f.logged_at)}</span><br/>
          Flagged Text: <code>"${f.flagged_message}"</code><br/>
          Rule Triggered: <strong>${f.violation_type} ("${f.matched_pattern}")</strong>
        </div>
      `).join('');

  const gpsHtml = gps.length === 0
    ? `<div style="color:var(--text-muted)">No GPS breadcrumbs logged yet. Click "+ Record Test GPS Telemetry Ping" to test Engine 05 telemetry trail.</div>`
    : gps.map(g => `
        <div style="padding:0.35rem 0;border-bottom:1px solid var(--border-subtle);font-size:0.76rem;">
          <strong>${formatTs(g.recorded_at)}</strong> •
          Coords: <code>${Number(g.lat).toFixed(5)}, ${Number(g.lng).toFixed(5)}</code> •
          Speed: <strong>${g.speed_kph} kph</strong> (Heading ${g.heading}°) •
          <span class="pill-clean">${g.sub_400cc_compliant ? 'Expressway-Free OK' : 'EXPRESSWAY ALERT'}</span>
        </div>
      `).join('');

  const body = document.getElementById('forensicModalBody');
  body.innerHTML = `
    <div class="forensic-grid">
      <div class="forensic-box">
        <h4>1. Exact Booking Lifecycle Timestamps (Engine 02 & 08)</h4>
        <ul class="ts-list">
          <li><strong>Booking Reference:</strong> <code>${b.booking_ref}</code> (${b.vertical_key})</li>
          <li><strong>Requested (created_at):</strong> ${formatTs(b.created_at)}</li>
          <li><strong>Claimed (accepted_at):</strong> ${formatTs(b.accepted_at)}</li>
          <li><strong>Arrived on Site (arrived_at):</strong> ${formatTs(b.arrived_at)}</li>
          <li><strong>Work Started (started_at):</strong> ${formatTs(b.started_at)}</li>
          <li><strong>Completed (completed_at):</strong> ${formatTs(b.completed_at)}</li>
          <li><strong>Maturity Window (expires_at):</strong> ${formatTs(b.expires_at)}</li>
        </ul>
      </div>

      <div class="forensic-box">
        <h4>2. Parties, Financial Hold & Spatial Verification</h4>
        <ul class="ts-list">
          <li><strong>Customer:</strong> ${client ? `<button type="button" class="username-link" onclick="closeForensicModal();openProfileInspector('${client.id}')">@${client.username}</button> ${client.full_name}` : b.client_id}</li>
          <li><strong>Contractor:</strong> ${contractor ? `<button type="button" class="username-link" onclick="closeForensicModal();openProfileInspector('${contractor.id}')">@${contractor.username}</button> ${contractor.full_name}` : 'Unassigned'}</li>
          <li><strong>Pickup / Site Address:</strong> ${b.pickup_address} (${b.barangay_zone}, ${b.municipality})</li>
          <li><strong>Pickup Coords:</strong> <code>${b.pickup_lat || 15.1450}, ${b.pickup_lng || 120.5887}</code></li>
          <li><strong>Quoted Service Fee:</strong> PHP ${Number(b.quoted_fee).toFixed(2)} (+PHP ${Number(b.tip_amount || 0).toFixed(2)} Tip)</li>
          <li><strong>Locked Commission Hold:</strong> PHP ${Number(b.commission_locked || 0).toFixed(2)}</li>
          <li><strong>Expressway-Free Route:</strong> ${b.non_expressway_route_verified ? 'Verified Non-Expressway' : 'Pending'}</li>
        </ul>
      </div>
    </div>

    <div class="forensic-grid">
      <div class="forensic-box">
        <h4>3. Engine 11 In-App Chat History (<code>booking_messages</code>)</h4>
        <div style="max-height:200px;overflow-y:auto;">${chatHtml}</div>
      </div>

      <div class="forensic-box">
        <h4>4. Blocked Chat Violations (<code>moderation_audit_logs</code>)</h4>
        <div style="max-height:200px;overflow-y:auto;">${flagsHtml}</div>
      </div>
    </div>

    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.5rem;">
        <h4>5. Engine 05 GPS Telemetry Breadcrumbs (<code>booking_telemetry</code>)</h4>
        <button class="btn-xs" onclick="simulateTelemetryPing('${b.id}', '${b.contractor_id || '33333333-3333-3333-3333-333333333333'}')">
          + Record Test GPS Telemetry Ping
        </button>
      </div>
      <div style="max-height:180px;overflow-y:auto;">${gpsHtml}</div>
    </div>

    <div class="forensic-box" style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.6rem;">
      <div>
        <strong style="color:var(--amber-core)">Operator Enforcement & Dispute Resolution Actions:</strong>
        <div style="font-size:0.75rem;color:var(--text-muted)">Take immediate action based on timestamp, chat, and GPS evidence.</div>
      </div>
      <div class="action-cluster">
        ${Number(b.commission_locked || 0) > 0 && b.status !== 'COMPLETED' && b.status !== 'CANCELLED'
          ? `<button class="btn-danger" onclick="reverseBookingDispute('${b.id}', '${b.booking_ref}')">Reverse Commission Hold & Cancel</button>`
          : ''}
        ${contractor
          ? `<button class="btn-danger" onclick="closeForensicModal();openProfileInspector('${contractor.id}')">Open Contractor Profile to Suspend/Debit</button>`
          : ''}
        <button class="btn-xs" style="border-color:var(--amber-core);color:var(--amber-core);" onclick="operatorTriggerMutualCancel('${b.id}', '${b.booking_ref}')">50/50 Mutual Cancel</button>
        <button class="btn-xs" style="border-color:var(--crimson-err);color:#fca5a5;" onclick="operatorTriggerNoShowCancel('${b.id}', '${b.booking_ref}')">30m No-Show Strike</button>
        <button class="btn-outline" onclick="window.print()">Print / Export Dossier PDF</button>
      </div>
    </div>
  `;

  document.getElementById('forensicModal').style.display = 'flex';
}

function closeForensicModal() {
  document.getElementById('forensicModal').style.display = 'none';
}

async function simulateTelemetryPing(bookingId, contractorId) {
  const jitterLat = 15.1450 + (Math.random() * 0.015);
  const jitterLng = 120.5887 + (Math.random() * 0.015);
  const speed = +(20 + Math.random() * 25).toFixed(1);

  const { error } = await supabaseClient.rpc('record_booking_telemetry_rpc', {
    p_booking_id: bookingId,
    p_contractor_id: contractorId,
    p_lat: jitterLat,
    p_lng: jitterLng,
    p_speed_kph: speed,
    p_heading: 65.0
  });
  if (error) {
    alert('Telemetry RPC Error: ' + error.message);
    return;
  }
  await syncFromSupabase();
  openForensicDossier(bookingId);
}

// ============================================================================
// 7. SUPERADMIN CONTROL CENTER (UNIVERSAL LEDGER HEALTH & SUBSCRIPTION TIERS)
// ============================================================================

function renderSuperAdmin() {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') return;

  // 1. Render Universal Ledger Health Executive View (v_universal_ledger_health)
  const hStrip = document.getElementById('universalLedgerHealthStrip');
  const h = consoleState.ledgerHealth || {};
  if (hStrip) {
    hStrip.innerHTML = `
      <div class="kpi-card">
        <div class="kpi-label">Gross Paid Credit Allocations</div>
        <div class="kpi-value" style="color:var(--emerald-ok)">PHP ${Number(h.gross_paid_credit_allocations || 0).toFixed(2)}</div>
        <div class="kpi-sub">PayMongo QR Ph Cash-In Volume</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">Subscription Pass Volume</div>
        <div class="kpi-value" style="color:var(--cyan-accent)">PHP ${Number(h.gross_subscription_volume || 0).toFixed(2)}</div>
        <div class="kpi-sub">${Number(h.active_subscription_passes || 0)} Active Subscription Passes</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">Realized Commission Burned</div>
        <div class="kpi-value" style="color:var(--amber-core)">PHP ${Number(h.realized_commission_credits_burned || 0).toFixed(2)}</div>
        <div class="kpi-sub">Earned Platform Commission Credits</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">Promotional Bonus Burn</div>
        <div class="kpi-value" style="color:#fca5a5">PHP ${Number(h.promotional_bonus_burn || 0).toFixed(2)}</div>
        <div class="kpi-sub">+₱25 Top-Up & Pass Cushions</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">Unearned Wallet Liability</div>
        <div class="kpi-value">PHP ${Number(h.unearned_wallet_liability || 0).toFixed(2)}</div>
        <div class="kpi-sub">Total Held + Available Contractor Credits</div>
      </div>
      <div class="kpi-card" style="border-color:var(--emerald-ok);">
        <div class="kpi-label">Net Platform Yield</div>
        <div class="kpi-value" style="color:var(--emerald-ok)">PHP ${Number(h.net_platform_yield || 0).toFixed(2)}</div>
        <div class="kpi-sub">Subscriptions + Realized Comm - Promo Burn</div>
      </div>
    `;
  }


  // Render Dynamic Service Class Pricing Matrix Table
  const pBody = document.getElementById('superAdminPricingMatrixTable');
  if (pBody) {
    pBody.innerHTML = consoleState.servicePricingMatrix.map(p => {
      const baseFee = Number(p.base_or_callout_fee || 0);
      const incKm = Number(p.base_included_km || 0);
      const perKm = Number(p.per_km_rate || 0);
      const mult = Number(p.economy_multiplier || 1.0);
      const cancelFee = Number(p.arrival_cancellation_fee || 0);
      const extraKm = p.vertical_key === 'skills_marketplace' ? 0 : Math.max(0, 5.0 - incKm);
      const sample5km = ((baseFee + (extraKm * perKm)) * mult).toFixed(2);

      return `
        <tr>
          <td>
            <strong>${p.class_name}</strong><br/>
            <code>${p.class_code}</code> • <small style="color:var(--cyan-accent)">${p.vertical_key}</small><br/>
            <small style="color:var(--text-muted)">${p.vehicle_or_trade_type}</small>
          </td>
          <td>
            <input type="number" class="tier-input" style="width:75px;" id="pr_rad_${p.class_code}" value="${p.dispatch_radius_m}" step="100" />
          </td>
          <td>
            PHP <input type="number" class="tier-input" style="width:85px;" id="pr_base_${p.class_code}" value="${baseFee.toFixed(2)}" step="5" />
          </td>
          <td>
            <input type="number" class="tier-input" style="width:65px;" id="pr_inckm_${p.class_code}" value="${incKm.toFixed(1)}" step="0.5" /> km
          </td>
          <td>
            PHP <input type="number" class="tier-input" style="width:75px;" id="pr_km_${p.class_code}" value="${perKm.toFixed(2)}" step="1" />
          </td>
          <td>
            <input type="number" class="tier-input" style="width:70px;color:var(--amber-core);" id="pr_mult_${p.class_code}" value="${mult.toFixed(2)}" step="0.05" min="0.50" max="3.00" />x
          </td>
          <td>
            PHP <input type="number" class="tier-input" style="width:75px;" id="pr_canc_${p.class_code}" value="${cancelFee.toFixed(2)}" step="5" />
          </td>
          <td>
            <strong style="color:var(--emerald-ok)">PHP ${sample5km}</strong><br/>
            <small style="color:var(--text-muted)">${p.vertical_key === 'skills_marketplace' ? 'Base Call-Out' : '5.0 km Trip'}</small>
          </td>
          <td>
            <button class="btn-primary" style="padding:0.4rem 0.7rem;font-size:0.76rem;" onclick="saveServicePricingRow('${p.class_code}')">
              Save Pricing
            </button>
          </td>
        </tr>
      `;
    }).join('');
  }

  // 2. Render Editable 4-Tier Subscription Pass Table
  const subBody = document.getElementById('superAdminSubscriptionTiersTable');
  if (subBody) {
    subBody.innerHTML = consoleState.subscriptionTiers.map(t => `
      <tr>
        <td>
          <strong>${t.tier_name}</strong><br/>
          <code>${t.tier_code}</code>
          ${t.grants_vip_badge ? `<span class="vip-badge">VIP BADGE</span>` : ''}
        </td>
        <td>
          <input type="number" class="tier-input" id="fee_${t.tier_code}" value="${Number(t.monthly_fee).toFixed(2)}" step="100" />
        </td>
        <td>
          <input type="number" class="tier-input" id="cap_${t.tier_code}" value="${t.daily_active_cap_hours}" min="1" max="24" /> hrs/day
        </td>
        <td>
          <input type="number" class="tier-input" style="width:60px;" id="val_${t.tier_code}" value="${t.validity_days}" /> +
          <input type="number" class="tier-input" style="width:55px;" id="bon_${t.tier_code}" value="${t.bonus_days}" /> bonus
        </td>
        <td>
          +PHP <input type="number" class="tier-input" id="act_${t.tier_code}" value="${Number(t.activation_bonus_credits).toFixed(2)}" step="25" />
        </td>
        <td>
          +PHP <input type="number" class="tier-input" id="top_${t.tier_code}" value="${Number(t.topup_bonus_credits).toFixed(2)}" step="5" />
        </td>
        <td style="font-size:0.74rem;color:var(--text-muted);max-width:290px;">
          ${t.perks_summary}
        </td>
        <td>
          <button class="btn-primary" style="padding:0.4rem 0.7rem;font-size:0.76rem;" onclick="saveSubscriptionTierRow('${t.tier_code}')">
            Save Tier
          </button>
        </td>
      </tr>
    `).join('');
  }

  // 3. Render Service Verticals
  const list = document.getElementById('superAdminVerticalList');
  if (list) {
    list.innerHTML = consoleState.verticals.map(v => `
      <div class="vertical-row">
        <div>
          <h4>${v.display_name} <small style="color:var(--cyan-accent)">[${v.dispatch_mode}]</small></h4>
          <p>${v.description}</p>
        </div>
        <div>
          <button class="${v.is_enabled ? 'btn-primary' : 'btn-outline'}" onclick="toggleVertical('${v.vertical_key}')">
            ${v.is_enabled ? 'ACTIVE (Click to Turn OFF)' : 'LOCKED / OFF (Click to Turn ON)'}
          </button>
        </div>
      </div>
    `).join('');
  }

  // 4. Render Moderation Rules & Audit Logs
  const rulesBody = document.getElementById('moderationRulesTable');
  if (rulesBody) {
    rulesBody.innerHTML = consoleState.moderationRules.map(r => `
      <tr>
        <td><code>${r.rule_type}</code></td>
        <td><strong>"${r.pattern_or_word}"</strong></td>
        <td><span class="engine-tag">${(r.action_type || 'block').toUpperCase()}</span></td>
        <td><button class="btn-danger" onclick="removeModerationRule('${r.id}')">Remove</button></td>
      </tr>
    `).join('');
  }

  const auditBody = document.getElementById('moderationAuditTable');
  if (auditBody) {
    if (consoleState.moderationAuditLogs.length === 0) {
      auditBody.innerHTML = `<tr><td colspan="5" style="color:var(--text-muted)">Zero chat moderation infractions recorded in PostgreSQL.</td></tr>`;
    } else {
      auditBody.innerHTML = consoleState.moderationAuditLogs.map(log => `
        <tr>
          <td>${formatTs(log.logged_at)}</td>
          <td><code>${log.booking_id ? log.booking_id.slice(0, 8) : 'BOOKING'}</code></td>
          <td>${getUserLabel(log.sender_id)}</td>
          <td style="color:#fca5a5">"${log.flagged_message}"</td>
          <td><code>${log.violation_type}: "${log.matched_pattern}"</code></td>
        </tr>
      `).join('');
    }
  }
}

async function saveSubscriptionTierRow(tierCode) {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') {
    alert('UNAUTHORIZED: Only SuperAdmin can modify Subscription Tier Pricing & Caps.');
    return;
  }
  const fee = parseFloat(document.getElementById('fee_' + tierCode).value) || 0;
  const cap = parseInt(document.getElementById('cap_' + tierCode).value, 10) || 24;
  const valDays = parseInt(document.getElementById('val_' + tierCode).value, 10) || 30;
  const bonDays = parseInt(document.getElementById('bon_' + tierCode).value, 10) || 0;
  const actBonus = parseFloat(document.getElementById('act_' + tierCode).value) || 0;
  const topBonus = parseFloat(document.getElementById('top_' + tierCode).value) || 0;

  const { data, error } = await supabaseClient.rpc('admin_update_subscription_tier_rpc', {
    p_actor_id: currentAuthUser.id,
    p_tier_code: tierCode,
    p_monthly_fee: fee,
    p_daily_cap_hours: cap,
    p_validity_days: valDays,
    p_bonus_days: bonDays,
    p_activation_bonus: actBonus,
    p_topup_bonus: topBonus
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Subscription Tier Update Error: ' + (error ? error.message : data.message));
    return;
  }

  alert(`Subscription Tier [${tierCode}] updated live in PostgreSQL!`);
  await syncFromSupabase();
}

async function toggleVertical(verticalKey) {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') {
    alert('UNAUTHORIZED: Only SuperAdmin can enable or disable Service Verticals.');
    return;
  }
  const target = consoleState.verticals.find(v => v.vertical_key === verticalKey);
  if (!target) return;
  const nextState = !target.is_enabled;
  target.is_enabled = nextState;
  renderAll();

  const { data, error } = await supabaseClient.rpc('admin_toggle_service_vertical', {
    p_admin_id: currentAuthUser.id,
    p_vertical_key: verticalKey,
    p_is_enabled: nextState
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('RPC Error: ' + (error ? error.message : data.message));
  }
  await syncFromSupabase();
}

async function saveRuntimeConfig() {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') {
    alert('UNAUTHORIZED: Only SuperAdmin can set Global Commission Rates.');
    return;
  }
  const comm = parseFloat(document.getElementById('cfgCommission').value) || 12;
  if (comm > 12) {
    alert('Per V3 Spec, Global Commission Rate is capped at <= 12.0%.');
    return;
  }
  const mat = parseInt(document.getElementById('cfgMaturity').value, 10) || 72;
  const rateDecimal = (comm / 100.0).toFixed(4);

  const { data, error } = await supabaseClient.rpc('admin_update_system_config_rpc', {
    p_commission_rate: String(rateDecimal),
    p_maturity_hours: String(mat),
    p_actor_id: currentAuthUser.id
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('RPC Error: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
  alert(`SuperAdmin Committed Global Commission Rate = ${comm}%, Skills Board Maturity = ${mat} hours.`);
}

async function addModerationRule() {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') return;
  const ruleType = document.getElementById('newRuleType').value;
  const pattern = document.getElementById('newRulePattern').value.trim();
  if (!pattern || !supabaseClient) return;

  const { data, error } = await supabaseClient.rpc('admin_manage_moderation_rule_rpc', {
    p_operation: 'ADD',
    p_rule_type: ruleType,
    p_pattern: pattern.toLowerCase(),
    p_action_type: 'block',
    p_actor_id: currentAuthUser.id
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('RPC Error: ' + (error ? error.message : data.message));
    return;
  }
  document.getElementById('newRulePattern').value = '';
  await syncFromSupabase();
}

async function removeModerationRule(id) {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') return;
  const { data, error } = await supabaseClient.rpc('admin_manage_moderation_rule_rpc', {
    p_operation: 'DELETE',
    p_rule_id: id,
    p_actor_id: currentAuthUser.id
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('RPC Error: ' + (error ? error.message : data.message));
  }
  await syncFromSupabase();
}

function renderLguAdmin() {
  const tbody = document.getElementById('lguContractorTable');
  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  if (tbody) {
    tbody.innerHTML = contractors.map(c => {
      const certs = Array.isArray(c.certifications_docs) ? c.certifications_docs : [];
      return `
        <tr>
          <td>
            <button type="button" class="username-link" onclick="openProfileInspector('${c.id}')">@${c.username || 'contractor'}</button><br/>
            <strong>${c.full_name}</strong>
          </td>
          <td>${c.barangay_zone}, ${c.municipality || 'Angeles City'}</td>
          <td>${(c.skill_categories || []).join(', ')} • <span class="engine-tag">${certs.length} Cert(s)</span></td>
          <td>PHP ${Number(c.credit_balance || 0).toFixed(2)} (Held: PHP ${Number(c.held_credits || 0).toFixed(2)})</td>
          <td>
            <span class="${c.account_status === 'ACTIVE' ? 'pill-clean' : 'pill-watch'}">${c.account_status || 'ACTIVE'}</span>
          </td>
          <td>
            <button class="btn-outline" onclick="toggleContractorEndorsement('${c.id}', ${!c.lgu_peso_endorsed})">
              ${c.lgu_peso_endorsed ? 'Suspend Endorsement' : 'Grant Endorsement'}
            </button>
          </td>
        </tr>
      `;
    }).join('');
  }

  const opBody = document.getElementById('operatorAuditLogsTable');
  if (opBody) {
    if (consoleState.operatorAuditLogs.length === 0) {
      opBody.innerHTML = `<tr><td colspan="3" style="color:var(--text-muted)">No operator actions logged yet.</td></tr>`;
    } else {
      opBody.innerHTML = consoleState.operatorAuditLogs.map(l => `
        <tr>
          <td>${formatTs(l.created_at)}</td>
          <td><span class="engine-tag">${l.action_type}</span></td>
          <td>${l.action_summary}</td>
        </tr>
      `).join('');
    }
  }
}

function renderScopedAccountSummary() {
  const card = document.getElementById('scopedAccountSummaryCard');
  if (!card || !currentAuthUser) return;
  const selfRecord = getUserById(currentAuthUser.id) || currentAuthUser;
  const myBookings = consoleState.bookings.filter(b => b.client_id === selfRecord.id || b.contractor_id === selfRecord.id);

  card.innerHTML = `
    <div class="card-header">
      <h2>Authenticated Session • ${selfRecord.full_name} (@${selfRecord.username || selfRecord.role})</h2>
      <span class="engine-tag">ROLE: ${selfRecord.role.toUpperCase()} | STATE: ${selfRecord.account_status || 'ACTIVE'}</span>
    </div>
    <div style="font-size:0.83rem;color:var(--text-muted);display:flex;gap:1.5rem;flex-wrap:wrap;">
      <div><strong>Phone:</strong> ${selfRecord.phone}</div>
      <div><strong>GPS Home Anchor:</strong> ${selfRecord.barangay_zone}, ${selfRecord.municipality} (${Number(selfRecord.home_lat || 15.145).toFixed(4)}, ${Number(selfRecord.home_lng || 120.588).toFixed(4)})</div>
      ${selfRecord.role === 'contractor' ? `<div><strong>Wallet:</strong> <span style="color:var(--emerald-ok);font-weight:700;">PHP ${Number(selfRecord.credit_balance || 0).toFixed(2)}</span></div>` : ''}
      <div><strong>Bookings:</strong> ${myBookings.length}</div>
    </div>
  `;
}

async function toggleContractorEndorsement(cId, nextEndorsed) {
  if (!supabaseClient || !currentAuthUser) return;
  const { error } = await supabaseClient.rpc('lgu_toggle_contractor_endorsement_rpc', {
    p_contractor_id: cId,
    p_lgu_endorsed: nextEndorsed
  });
  if (error) alert('RPC Error: ' + error.message);
  await syncFromSupabase();
}

async function reverseBookingDispute(bookingId, bookingRef) {
  if (!currentAuthUser) return;
  const reason = prompt(`Engine 09 Dispute Vault — Enter audit reason for reversing the commission hold on ${bookingRef}:`, 'Verified customer/contractor complaint resolution');
  if (!reason || !supabaseClient) return;
  const { error } = await supabaseClient.rpc('admin_resolve_dispute_reversal_rpc', {
    p_booking_id: bookingId,
    p_admin_id: currentAuthUser.id,
    p_resolution_notes: reason
  });
  if (error) alert('Dispute RPC Error: ' + error.message);
  closeForensicModal();
  await syncFromSupabase();
}

function renderAll() {
  if (!currentAuthUser) return;
  renderVerticalStrip();
  if (['super_admin', 'admin'].includes(currentAuthUser.role)) {
    renderOperatorKpis();
    renderOperatorUsersTable();
    renderOperatorKycQueue();
    renderOperatorContractorCommissionTable();
    renderOperatorHeatmapTable();
    renderOperatorLiveSpatialMap();
    renderOperatorPushOutboxFeed();
    renderOperatorLtfrbMonitor();
    renderOperatorBookingsTable();
    renderLguAdmin();
  }
  if (currentAuthUser.role === 'super_admin') {
    renderSuperAdmin();
    renderPayMongoGatewayTab();
  }
  renderScopedAccountSummary();
}

window.addEventListener('DOMContentLoaded', async () => {
  if (window.supabase) {
    supabaseClient = window.supabase.createClient(_K_U, _K_A);
  }
  const savedSession = sessionStorage.getItem(SESSION_STORAGE_KEY);
  if (savedSession) {
    try {
      currentAuthUser = JSON.parse(savedSession);
      await unlockConsoleForAuthenticatedUser();
    } catch (_e) {
      sessionStorage.removeItem(SESSION_STORAGE_KEY);
    }
  }
});


async function saveServicePricingRow(classCode) {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') {
    alert('UNAUTHORIZED: Only SuperAdmin can modify service class pricing.');
    return;
  }
  const rad = parseInt(document.getElementById('pr_rad_' + classCode).value, 10) || 1500;
  const baseFee = parseFloat(document.getElementById('pr_base_' + classCode).value) || 0;
  const incKm = parseFloat(document.getElementById('pr_inckm_' + classCode).value) || 0;
  const perKm = parseFloat(document.getElementById('pr_km_' + classCode).value) || 0;
  const mult = parseFloat(document.getElementById('pr_mult_' + classCode).value) || 1.0;
  const cancelFee = parseFloat(document.getElementById('pr_canc_' + classCode).value) || 0;

  const { data, error } = await supabaseClient.rpc('secure_admin_update_service_pricing_rpc', {
    p_session_token: getActiveSessionToken(),
    p_class_code: classCode,
    p_base_fee: baseFee,
    p_included_km: incKm,
    p_per_km_rate: perKm,
    p_economy_multiplier: mult,
    p_cancellation_fee: cancelFee,
    p_dispatch_radius_m: rad
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Pricing Update Error (Ensure Step 1 SQL 07_alisto_dynamic_pricing_matrix.sql is executed in Supabase): ' + (error ? error.message : data.message));
    return;
  }

  alert(`Pricing for [${classCode}] committed live to PostgreSQL!`);
  await syncFromSupabase();
}

async function applyBlanketEconomyMultiplier() {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') {
    alert('UNAUTHORIZED: Only SuperAdmin can apply platform economic multipliers.');
    return;
  }
  const targetVert = document.getElementById('econTargetVertical').value;
  const mult = parseFloat(document.getElementById('econMultiplierInput').value) || 1.00;
  const reason = document.getElementById('econReasonInput').value.trim() || 'SuperAdmin economic/fuel index adjustment';

  const { data, error } = await supabaseClient.rpc('secure_admin_apply_economy_multiplier_rpc', {
    p_session_token: getActiveSessionToken(),
    p_target_vertical: targetVert,
    p_multiplier: mult,
    p_reason: reason
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Multiplier RPC Error: ' + (error ? error.message : data.message));
    return;
  }

  alert(`Applied ${mult.toFixed(2)}x Economy Multiplier across [${targetVert}]!`);
  await syncFromSupabase();
}

async function resetBlanketEconomyMultiplier() {
  document.getElementById('econTargetVertical').value = 'ALL';
  document.getElementById('econMultiplierInput').value = '1.00';
  document.getElementById('econReasonInput').value = 'Reset to 1.00x baseline pricing';
  await applyBlanketEconomyMultiplier();
}


function renderOperatorHeatmapTable() {
  const tbody = document.getElementById('operatorHeatmapTable');
  if (!tbody) return;
  if (!consoleState.heatmapClusters || consoleState.heatmapClusters.length === 0) {
    tbody.innerHTML = '<tr><td colspan="6" style="color:var(--text-muted)">No demand clusters recorded yet.</td></tr>';
    return;
  }
  tbody.innerHTML = consoleState.heatmapClusters.map(h => {
    const badgeClass = h.heat_intensity === 'HIGH_SURGE_ZONE'
      ? 'pill-risk'
      : (h.heat_intensity === 'MODERATE_DEMAND' ? 'pill-watch' : 'pill-clean');
    const navUrl = `https://www.google.com/maps/dir/?api=1&destination=${h.center_lat},${h.center_lng}&travelmode=driving&avoid=highways,tolls`;
    return `
      <tr>
        <td><strong>${h.barangay_zone}, ${h.municipality}</strong></td>
        <td><code>${h.vertical_key}</code></td>
        <td>
          <a href="${navUrl}" target="_blank" class="username-link" title="Launch Google Maps with avoid=ht (No Expressways/Tolls)">
            NAV (${h.center_lat}, ${h.center_lng} • avoid=ht)
          </a>
        </td>
        <td><strong>${h.total_bookings_volume}</strong></td>
        <td><strong style="color:var(--cyan-accent)">${h.active_demand_count} Active</strong></td>
        <td><span class="${badgeClass}">${h.heat_intensity}</span></td>
      </tr>
    `;
  }).join('');
}

async function operatorTriggerMutualCancel(bookingId, bookingRef) {
  const reason = prompt(`Execute Mutual Cancellation (50/50 Commission Recovery Split) for ${bookingRef}:`, 'Counterparty-confirmed mutual cancellation');
  if (!reason || !currentAuthUser) return;
  const { data, error } = await supabaseClient.rpc('secure_cancel_booking_rpc', {
    p_booking_id: bookingId,
    p_session_token: getActiveSessionToken(),
    p_cancel_mode: 'MUTUAL_CONFIRMED',
    p_reason: reason
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Mutual Cancel Error: ' + (error ? error.message : data.message));
    return;
  }
  alert(`Mutual Cancellation Executed! Mode: ${data.mode} (Contractor 50% Split: PHP ${data.contractor_deducted_php || 0} | Customer 50% Clearing Fee: PHP ${data.customer_clearing_fee_php || 0})`);
  closeForensicModal();
  await syncFromSupabase();
}

async function operatorTriggerNoShowCancel(bookingId, bookingRef) {
  if (!confirm(`Confirm Contractor No-Show (>30 mins) on ${bookingRef}? This unlocks penalty-free customer cancellation and logs 1 accountability strike on the contractor.`)) return;
  const { data, error } = await supabaseClient.rpc('secure_cancel_booking_rpc', {
    p_booking_id: bookingId,
    p_session_token: getActiveSessionToken(),
    p_cancel_mode: 'CONTRACTOR_NO_SHOW_30MIN',
    p_reason: 'Contractor no-show past 30 minutes'
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('No-Show Cancel Error: ' + (error ? error.message : data.message));
    return;
  }
  alert('30-Minute No-Show Cancellation completed & accountability strike logged.');
  closeForensicModal();
  await syncFromSupabase();
}


async function revokeUserSessions(userId, label) {
  if (!confirm(`Revoke all active Web & Mobile sessions for ${label}? They will be forced to sign in again immediately.`)) return;
  const { data, error } = await supabaseClient.rpc('admin_revoke_user_sessions_rpc', {
    p_session_token: getActiveSessionToken(),
    p_target_user_id: userId
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Session Kill-Switch Error: ' + (error ? error.message : data.message));
    return;
  }
  alert(`Kill-Switch Executed: Revoked ${data.revoked_count || 0} active session(s) for ${label}.`);
  await syncFromSupabase();
}


// ============================================================================
// PHASE 2B: SUPERADMIN PAYMENT GATEWAY & PAYMONGO VAULT CONTROLS
// ============================================================================

let paymongoFormHydrated = false;
let paymongoFormDirty = false;
let selectedPaymentModeDraft = 'MANUAL';

function markPayMongoFormDirty() {
  paymongoFormDirty = true;
}

function selectPaymentModeOption(mode) {
  selectedPaymentModeDraft = mode;
  paymongoFormDirty = true;
  const rManual = document.getElementById('pgModeManual');
  const rTest = document.getElementById('pgModeTest');
  const rLive = document.getElementById('pgModeLive');
  if (rManual) rManual.checked = (mode === 'MANUAL');
  if (rTest) rTest.checked = (mode === 'PAYMONGO_TEST');
  if (rLive) rLive.checked = (mode === 'PAYMONGO_LIVE');

  const styles = {
    pgCardManual: mode === 'MANUAL' ? '#fbbf24' : '#334155',
    pgCardTest: mode === 'PAYMONGO_TEST' ? '#38bdf8' : '#334155',
    pgCardLive: mode === 'PAYMONGO_LIVE' ? '#34d399' : '#334155'
  };
  Object.entries(styles).forEach(([id, border]) => {
    const el = document.getElementById(id);
    if (el) el.style.borderColor = border;
  });
}

function toggleSecretInput(inputId, btnEl) {
  const inp = document.getElementById(inputId);
  if (!inp) return;
  const revealing = inp.type === 'password';
  inp.type = revealing ? 'text' : 'password';
  if (btnEl) btnEl.textContent = revealing ? 'Hide' : 'Show';
}

function copyGatewayField(inputId, btnEl) {
  const inp = document.getElementById(inputId);
  if (!inp || !inp.value) return;
  navigator.clipboard.writeText(inp.value).then(() => {
    if (!btnEl) return;
    const orig = btnEl.textContent;
    btnEl.textContent = 'Copied';
    setTimeout(() => { btnEl.textContent = orig; }, 1400);
  }).catch(() => {
    inp.select();
    document.execCommand('copy');
  });
}

function hydratePayMongoFormFields(cfg) {
  if (!cfg) return;
  const setVal = (id, val) => {
    const el = document.getElementById(id);
    if (el) el.value = val || '';
  };

  setVal('pgTestApiUrl', cfg.test_api_base_url || 'https://api.paymongo.com/v1');
  setVal('pgTestPublicKey', cfg.test_public_key || '');
  setVal('pgTestSecretKey', cfg.test_secret_key || '');
  setVal('pgTestWebhookSecret', cfg.test_webhook_secret || '');
  setVal('pgTestWebhookUrl', cfg.test_webhook_url || '');

  setVal('pgLiveApiUrl', cfg.live_api_base_url || 'https://api.paymongo.com/v1');
  setVal('pgLivePublicKey', cfg.live_public_key || '');
  setVal('pgLiveSecretKey', cfg.live_secret_key || '');
  setVal('pgLiveWebhookSecret', cfg.live_webhook_secret || '');
  setVal('pgLiveWebhookUrl', cfg.live_webhook_url || '');

  setVal('pgCheckoutEdgeUrl', cfg.checkout_edge_url || '');
  setVal('pgCheckoutReturnUrl', cfg.checkout_return_url || '');
  setVal('pgManualInstructions', cfg.manual_payment_instructions || '');

  selectPaymentModeOption(cfg.payment_mode || 'MANUAL');
  paymongoFormHydrated = true;
  paymongoFormDirty = false;
}

async function reloadPayMongoVaultForm() {
  paymongoFormHydrated = false;
  paymongoFormDirty = false;
  await syncFromSupabase();
}

function renderPayMongoGatewayTab() {
  const cfg = consoleState.paymentGatewayConfig;
  const badge = document.getElementById('pgActiveModeBadge');
  const updatedEl = document.getElementById('pgLastUpdatedLabel');

  if (cfg) {
    const mode = cfg.payment_mode || 'MANUAL';
    if (badge) {
      badge.textContent = `ACTIVE MODE: ${mode}`;
      if (mode === 'PAYMONGO_LIVE') {
        badge.style.color = '#34d399';
        badge.style.borderColor = '#10b981';
      } else if (mode === 'PAYMONGO_TEST') {
        badge.style.color = '#38bdf8';
        badge.style.borderColor = '#0ea5e9';
      } else {
        badge.style.color = '#fbbf24';
        badge.style.borderColor = '#f59e0b';
      }
    }
    if (updatedEl) {
      updatedEl.textContent = `Last saved: ${formatTs(cfg.updated_at)} | Test Secret: ${cfg.test_secret_key ? 'Configured' : 'Empty'} | Live Secret: ${cfg.live_secret_key ? 'Configured' : 'Empty'}`;
    }
    if (!paymongoFormHydrated && !paymongoFormDirty) {
      hydratePayMongoFormFields(cfg);
    }
  }

  renderPayMongoTransactionsTable();
}

function renderPayMongoTransactionsTable() {
  const tbody = document.getElementById('pgTransactionsTableBody');
  if (!tbody) return;
  const rows = consoleState.paymentTransactions || [];
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="9" style="padding:16px;text-align:center;color:#64748b;">No payment transactions recorded yet.</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map(tx => {
    const userLabel = getUserLabel(tx.user_id);
    const statusColor = tx.status === 'PAID'
      ? '#34d399'
      : (tx.status === 'REJECTED' || tx.status === 'FAILED' ? '#f87171' : '#fbbf24');
    const canReview = tx.status === 'MANUAL_SUBMITTED' || (tx.payment_mode === 'MANUAL' && tx.status === 'PENDING');
    const actionsHtml = canReview
      ? `<button class="btn-outline" style="border-color:#10b981;color:#34d399;margin-right:6px;" onclick="reviewManualPaymentTx('${tx.id}', 'APPROVE', '${tx.tx_ref}')">Approve</button>` +
        `<button class="btn-outline" style="border-color:#ef4444;color:#fca5a5;" onclick="reviewManualPaymentTx('${tx.id}', 'REJECT', '${tx.tx_ref}')">Reject</button>`
      : `<span style="color:#64748b;">—</span>`;

    return `
      <tr style="border-bottom:1px solid #1e293b;">
        <td style="padding:9px 8px;font-weight:700;color:#e2e8f0;">${tx.tx_ref}</td>
        <td style="padding:9px 8px;color:#cbd5e1;">${userLabel}</td>
        <td style="padding:9px 8px;color:#38bdf8;">${tx.payment_mode}</td>
        <td style="padding:9px 8px;color:#e2e8f0;">${tx.purpose}${tx.tier_code ? ' (' + tx.tier_code + ')' : ''}</td>
        <td style="padding:9px 8px;font-weight:700;color:#f8fafc;">PHP ${Number(tx.amount_php || 0).toFixed(2)}</td>
        <td style="padding:9px 8px;color:#cbd5e1;">${tx.provider_reference || '—'}</td>
        <td style="padding:9px 8px;font-weight:700;color:${statusColor};">${tx.status}</td>
        <td style="padding:9px 8px;color:#94a3b8;">${formatTs(tx.created_at)}</td>
        <td style="padding:9px 8px;white-space:nowrap;">${actionsHtml}</td>
      </tr>
    `;
  }).join('');
}

async function savePayMongoGatewayConfig() {
  const mode = selectedPaymentModeDraft || 'MANUAL';
  const testSecret = (document.getElementById('pgTestSecretKey')?.value || '').trim();
  const liveSecret = (document.getElementById('pgLiveSecretKey')?.value || '').trim();

  if (mode === 'PAYMONGO_TEST' && !testSecret) {
    alert('Cannot switch to PAYMONGO_TEST without a Test Secret Key (sk_test_...).');
    return;
  }
  if (mode === 'PAYMONGO_LIVE') {
    if (!liveSecret) {
      alert('Cannot switch to PAYMONGO_LIVE without a Live Secret Key (sk_live_...).');
      return;
    }
    if (!confirm('Switch Alisto Network PH to PAYMONGO_LIVE production payments? Real PHP charges will be processed immediately.')) {
      return;
    }
  }

  const payload = {
    p_session_token: getActiveSessionToken(),
    p_payment_mode: mode,
    p_test_api_base_url: (document.getElementById('pgTestApiUrl')?.value || '').trim(),
    p_test_public_key: (document.getElementById('pgTestPublicKey')?.value || '').trim(),
    p_test_secret_key: testSecret,
    p_test_webhook_secret: (document.getElementById('pgTestWebhookSecret')?.value || '').trim(),
    p_test_webhook_url: (document.getElementById('pgTestWebhookUrl')?.value || '').trim(),
    p_live_api_base_url: (document.getElementById('pgLiveApiUrl')?.value || '').trim(),
    p_live_public_key: (document.getElementById('pgLivePublicKey')?.value || '').trim(),
    p_live_secret_key: liveSecret,
    p_live_webhook_secret: (document.getElementById('pgLiveWebhookSecret')?.value || '').trim(),
    p_live_webhook_url: (document.getElementById('pgLiveWebhookUrl')?.value || '').trim(),
    p_checkout_edge_url: (document.getElementById('pgCheckoutEdgeUrl')?.value || '').trim(),
    p_checkout_return_url: (document.getElementById('pgCheckoutReturnUrl')?.value || '').trim(),
    p_manual_instructions: (document.getElementById('pgManualInstructions')?.value || '').trim()
  };

  const { data, error } = await supabaseClient.rpc('secure_update_payment_gateway_config_rpc', payload);
  if (error || (data && data.status !== 'SUCCESS')) {
    alert('Failed to save Payment Gateway Vault: ' + (error ? error.message : (data?.message || 'Unknown error')));
    return;
  }

  paymongoFormHydrated = false;
  paymongoFormDirty = false;
  await syncFromSupabase();
  alert(`Payment Gateway Vault saved cleanly. Active mode is now: ${mode}`);
}

async function reviewManualPaymentTx(txId, decision, txRef) {
  const note = decision === 'REJECT'
    ? (prompt(`Enter rejection reason for ${txRef}:`, 'Receipt reference could not be verified') || '')
    : 'Verified by Admin Console';
  if (decision === 'REJECT' && !note) return;
  if (decision === 'APPROVE' && !confirm(`Approve manual payment ${txRef} and immediately credit wallet / activate pass?`)) return;

  const { data, error } = await supabaseClient.rpc('secure_review_manual_payment_rpc', {
    p_session_token: getActiveSessionToken(),
    p_tx_id: txId,
    p_decision: decision,
    p_review_notes: note
  });

  if (error || (data && data.status !== 'SUCCESS')) {
    alert('Settlement Error: ' + (error ? error.message : (data?.message || 'Unknown error')));
    return;
  }

  alert(`Transaction ${txRef} marked as ${data.decision || decision}.`);
  await syncFromSupabase();
}


// ============================================================================
// PHASE 3B: LIVE OPERATIONS MAP, DEMAND HEAT MAP & ENGINE 06 PUSH OUTBOX
// ============================================================================

let alistoOpsMap = null;
let alistoOpsLayerGroup = null;
let alistoOpsHasAutoFitted = false;

function ensureOpsMapInitialized() {
  const mapEl = document.getElementById('alistoLiveOpsMap');
  if (!mapEl) return false;
  if (typeof window.L === 'undefined') {
    const stats = document.getElementById('alistoLiveOpsMapStats');
    if (stats) stats.textContent = 'Interactive map library unavailable offline; telemetry & heat map tables remain active.';
    return false;
  }
  if (!alistoOpsMap) {
    alistoOpsMap = window.L.map('alistoLiveOpsMap', { zoomControl: true }).setView([15.1450, 120.5887], 13);
    window.L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap &copy; CARTO | Alisto Spatial Engine (avoid=ht)'
    }).addTo(alistoOpsMap);
    alistoOpsLayerGroup = window.L.layerGroup().addTo(alistoOpsMap);
  }
  return true;
}

function centerOpsMapRegion3() {
  if (!ensureOpsMapInitialized()) return;
  alistoOpsMap.setView([15.1450, 120.5887], 13);
}

function fitOpsMapToActivePins() {
  if (!ensureOpsMapInitialized() || !alistoOpsLayerGroup) return;
  const bounds = [];
  alistoOpsLayerGroup.eachLayer(layer => {
    if (typeof layer.getLatLng === 'function') {
      bounds.push(layer.getLatLng());
    }
  });
  if (bounds.length > 0) {
    alistoOpsMap.fitBounds(window.L.latLngBounds(bounds).pad(0.25), { maxZoom: 15 });
  } else {
    centerOpsMapRegion3();
  }
}

function renderOperatorLiveSpatialMap() {
  if (!ensureOpsMapInitialized() || !alistoOpsLayerGroup) return;
  alistoOpsLayerGroup.clearLayers();

  const showTelemetry = document.getElementById('mapLayerTelemetry')?.checked !== false;
  const showBookings = document.getElementById('mapLayerBookings')?.checked !== false;
  const showHeatmap = document.getElementById('mapLayerHeatmap')?.checked !== false;

  let telemetryCount = 0;
  let bookingPinCount = 0;
  let clusterCount = 0;

  // 1. Demand Heat Map Clusters (bottom layer)
  if (showHeatmap) {
    (consoleState.heatmapClusters || []).forEach(c => {
      const lat = Number(c.centroid_lat ?? c.center_lat ?? c.lat ?? c.avg_lat);
      const lng = Number(c.centroid_lng ?? c.center_lng ?? c.lng ?? c.avg_lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
      const demand = Number(c.open_requests ?? c.active_demand ?? c.request_count ?? c.demand_count ?? c.booking_count ?? 1);
      const radiusMeters = Math.min(2400, 350 + (demand * 220));
      const color = demand >= 5 ? '#ef4444' : (demand >= 3 ? '#f59e0b' : '#38bdf8');

      window.L.circle([lat, lng], {
        radius: radiusMeters,
        color: color,
        weight: 1.5,
        fillColor: color,
        fillOpacity: 0.24
      }).bindPopup(
        `<div style="color:#0f172a;font-size:12px;">
          <strong>Demand Cluster: ${c.barangay_zone || c.municipality || 'Active Zone'}</strong><br/>
          Municipality: ${c.municipality || 'Philippines'}<br/>
          Active Demand: <b>${demand}</b> request(s)
        </div>`
      ).addTo(alistoOpsLayerGroup);
      clusterCount++;
    });
  }

  // 2. Active Bookings (middle layer)
  if (showBookings) {
    (consoleState.bookings || []).forEach(b => {
      if (['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(b.status)) return;
      const lat = Number(b.pickup_lat);
      const lng = Number(b.pickup_lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0)) return;

      window.L.circleMarker([lat, lng], {
        radius: 8,
        color: '#fbbf24',
        weight: 2,
        fillColor: '#f59e0b',
        fillOpacity: 0.85
      }).bindPopup(
        `<div style="color:#0f172a;font-size:12px;">
          <strong>${b.booking_ref} [${b.status}]</strong><br/>
          ${b.service_title || 'Service Booking'}<br/>
          ${b.pickup_address || b.barangay_zone || ''}<br/>
          Fee: PHP ${Number(b.quoted_fee || 0).toFixed(2)}<br/>
          <button style="margin-top:6px;padding:4px 8px;background:#0f172a;color:#00f2fe;border:none;border-radius:4px;cursor:pointer;" onclick="openForensicDossier('${b.id}')">Open Dossier</button>
        </div>`
      ).addTo(alistoOpsLayerGroup);
      bookingPinCount++;
    });
  }

  // 3. Live 10-Second Contractor GPS Telemetry Pings (top layer)
  if (showTelemetry) {
    (consoleState.liveTelemetryPins || []).forEach(p => {
      const lat = Number(p.lat);
      const lng = Number(p.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
      const riderName = getUserLabel(p.contractor_id);

      const speedNum = Number(p.speed_kph || 0);
      const isOverspeed = speedNum > 60;
      window.L.circleMarker([lat, lng], {
        radius: isOverspeed ? 11 : 9,
        color: isOverspeed ? '#fef08a' : '#ffffff',
        weight: 2.5,
        fillColor: isOverspeed ? '#ef4444' : '#00f2fe',
        fillOpacity: 0.96
      }).bindPopup(
        `<div style="color:#0f172a;font-size:12px;">
          <strong>${isOverspeed ? 'LTFRB OVERSPEED ALERT (>60 km/h)' : 'LIVE RIDER PING'} • ${p.booking_ref}</strong><br/>
          Contractor: ${riderName}<br/>
          State: <b>${p.booking_status}</b> | Speed: <b style="color:${isOverspeed ? '#dc2626' : '#0f172a'};">${speedNum.toFixed(1)} km/h</b><br/>
          Last Ping: ${formatTs(p.ping_time)}
        </div>`
      ).addTo(alistoOpsLayerGroup);
      telemetryCount++;
    });
  }

  const statsEl = document.getElementById('alistoLiveOpsMapStats');
  if (statsEl) {
    statsEl.textContent = `Live GPS Pings: ${telemetryCount} | Active Booking Pins: ${bookingPinCount} | Demand Heat Clusters: ${clusterCount}`;
  }

  if (!alistoOpsHasAutoFitted && (telemetryCount + bookingPinCount + clusterCount) > 0) {
    alistoOpsHasAutoFitted = true;
    setTimeout(() => {
      if (alistoOpsMap) {
        alistoOpsMap.invalidateSize();
        fitOpsMapToActivePins();
      }
    }, 200);
  }
}

function renderOperatorPushOutboxFeed() {
  const tbody = document.getElementById('operatorPushOutboxBody');
  if (!tbody) return;
  const rows = (consoleState.pushOutboxRecent || []).slice(0, 15);
  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="5" style="padding:14px;text-align:center;color:#64748b;">No Engine 06 notifications logged yet.</td></tr>';
    return;
  }

  tbody.innerHTML = rows.map(n => {
    const badgeColor = n.push_status === 'SENT' || n.push_status === 'DELIVERED_INAPP' ? '#34d399' : (n.push_status === 'FAILED' ? '#f87171' : '#fbbf24');
    return `
      <tr style="border-bottom:1px solid #1e293b;">
        <td style="padding:8px;font-weight:700;color:#38bdf8;">${n.event_type}</td>
        <td style="padding:8px;color:#e2e8f0;">${getUserLabel(n.recipient_id)}</td>
        <td style="padding:8px;color:#cbd5e1;"><strong>${n.title}</strong><br/><span style="color:#94a3b8;">${n.body}</span></td>
        <td style="padding:8px;font-weight:700;color:${badgeColor};">${n.push_status}${n.is_read ? ' (READ)' : ' (UNREAD)'}</td>
        <td style="padding:8px;color:#94a3b8;">${formatTs(n.created_at)}</td>
      </tr>
    `;
  }).join('');
}


// ============================================================================
// PHASE 5B: LTFRB 2026 COMPLIANCE MONITOR & 1-RIDER-1-BIKE PLATE BINDING
// ============================================================================

let ltfrbFormDirty = false;
let ltfrbSelectedContractorId = '';

function markLtfrbFormDirty() {
  ltfrbFormDirty = true;
}

function toggleLtfrbEvFields() {
  const mode = document.getElementById('ltfrbPowertrain')?.value || 'ICE';
  const iceWrap = document.getElementById('ltfrbIceCcWrap');
  const evWrap = document.getElementById('ltfrbEvKwWrap');
  if (iceWrap) iceWrap.style.display = (mode === 'ICE') ? 'block' : 'none';
  if (evWrap) evWrap.style.display = (mode === 'EV') ? 'block' : 'none';
}

function populateLtfrbContractorBindingForm(forceReload) {
  const sel = document.getElementById('ltfrbContractorSelect');
  if (!sel || !sel.value) return;
  if (!forceReload && ltfrbFormDirty && ltfrbSelectedContractorId === sel.value) return;

  ltfrbSelectedContractorId = sel.value;
  ltfrbFormDirty = false;

  const prof = getContractorProfile(sel.value);
  const plateInp = document.getElementById('ltfrbPlateNo');
  const ptSel = document.getElementById('ltfrbPowertrain');
  const ccInp = document.getElementById('ltfrbEngineCc');
  const kwInp = document.getElementById('ltfrbMotorKw');
  const ridInp = document.getElementById('ltfrbRiderId');
  const statusEl = document.getElementById('ltfrbBindingStatusLine');

  if (prof) {
    if (plateInp) plateInp.value = prof.authorized_plate_no || '';
    if (ptSel) ptSel.value = prof.is_electric_vehicle ? 'EV' : 'ICE';
    if (ccInp) ccInp.value = prof.engine_displacement_cc || 125;
    if (kwInp) kwInp.value = prof.electric_motor_kw || 2.0;
    if (ridInp) ridInp.value = prof.ltfrb_rider_id || '';
    toggleLtfrbEvFields();

    if (statusEl) {
      if (prof.ltfrb_compliant && prof.authorized_plate_no) {
        const spec = prof.is_electric_vehicle
          ? `EV ${prof.electric_motor_kw} kW`
          : `${prof.engine_displacement_cc}cc ICE`;
        statusEl.style.color = '#34d399';
        statusEl.textContent = `VERIFIED 1-RIDER-1-BIKE: Plate [${prof.authorized_plate_no}] (${spec}) • Verified ${formatTs(prof.ltfrb_verified_at)}`;
      } else {
        statusEl.style.color = '#fbbf24';
        statusEl.textContent = 'UNBOUND: No LTFRB 2026 authorized motorcycle plate bound to this contractor yet.';
      }
    }
  }
}

async function saveLtfrbVehicleBinding() {
  const contractorId = document.getElementById('ltfrbContractorSelect')?.value || '';
  const plateNo = (document.getElementById('ltfrbPlateNo')?.value || '').trim();
  const isEv = (document.getElementById('ltfrbPowertrain')?.value || 'ICE') === 'EV';
  const engineCc = parseInt(document.getElementById('ltfrbEngineCc')?.value || '0', 10);
  const motorKw = parseFloat(document.getElementById('ltfrbMotorKw')?.value || '0');
  const riderId = (document.getElementById('ltfrbRiderId')?.value || '').trim();

  if (!contractorId) {
    alert('Select a contractor account first.');
    return;
  }
  if (plateNo.length < 4) {
    alert('Enter a valid authorized motorcycle plate number.');
    return;
  }

  const { data, error } = await supabaseClient.rpc('secure_update_ltfrb_vehicle_binding_rpc', {
    p_session_token: getActiveSessionToken(),
    p_contractor_id: contractorId,
    p_plate_no: plateNo,
    p_is_electric: isEv,
    p_engine_cc: isEv ? 0 : engineCc,
    p_motor_kw: isEv ? motorKw : null,
    p_ltfrb_rider_id: riderId || null
  });

  if (error || (data && data.status !== 'SUCCESS')) {
    alert('LTFRB Compliance Gate: ' + (error ? error.message : (data?.message || 'Binding failed')));
    await syncFromSupabase();
    return;
  }

  ltfrbFormDirty = false;
  await syncFromSupabase();
  populateLtfrbContractorBindingForm(true);
  alert(`LTFRB 1-Rider-1-Bike binding saved cleanly for plate [${plateNo.toUpperCase()}].`);
}

function renderOperatorLtfrbMonitor() {
  // 1. Populate contractor selector without interrupting active edits
  const sel = document.getElementById('ltfrbContractorSelect');
  if (sel) {
    const contractors = (consoleState.users || []).filter(u => u.role === 'contractor');
    const prevVal = sel.value || ltfrbSelectedContractorId;
    const expectedOptions = contractors.map(c => {
      const p = getContractorProfile(c.id);
      const badge = (p && p.ltfrb_compliant && p.authorized_plate_no) ? `[${p.authorized_plate_no}]` : '[UNBOUND]';
      return `<option value="${c.id}">${c.full_name} (@${c.username}) ${badge}</option>`;
    }).join('');

    if (sel.innerHTML !== expectedOptions) {
      sel.innerHTML = expectedOptions || '<option value="">No contractors found</option>';
      if (prevVal && contractors.some(c => c.id === prevVal)) {
        sel.value = prevVal;
      }
    }
    if (sel.value) {
      populateLtfrbContractorBindingForm(false);
    }
  }

  // 2. Render LTFRB safety & overspeed events table
  const tbody = document.getElementById('operatorLtfrbEventsBody');
  if (!tbody) return;
  const events = (consoleState.ltfrbSafetyEvents || []).slice(0, 30);
  if (!events.length) {
    tbody.innerHTML = '<tr><td colspan="6" style="padding:14px;text-align:center;color:#64748b;">No LTFRB safety or overspeed events logged yet.</td></tr>';
    return;
  }

  tbody.innerHTML = events.map(ev => {
    const sevColor = ev.severity === 'CRITICAL' ? '#f87171' : (ev.severity === 'WARNING' ? '#fbbf24' : '#34d399');
    const detailCell = ev.recorded_speed_kph
      ? `<strong style="color:#f87171;">${Number(ev.recorded_speed_kph).toFixed(1)} km/h</strong> (Limit ${Number(ev.speed_limit_kph || 60).toFixed(0)})`
      : (ev.refusal_category || '—');

    return `
      <tr style="border-bottom:1px solid #1e293b;">
        <td style="padding:8px;font-weight:700;color:#38bdf8;">${ev.event_type}</td>
        <td style="padding:8px;font-weight:700;color:${sevColor};">${ev.severity}</td>
        <td style="padding:8px;color:#e2e8f0;">${ev.contractor_id ? getUserLabel(ev.contractor_id) : '—'}</td>
        <td style="padding:8px;color:#e2e8f0;">${detailCell}</td>
        <td style="padding:8px;color:#cbd5e1;">${ev.notes || '—'}</td>
        <td style="padding:8px;color:#94a3b8;">${formatTs(ev.created_at)}</td>
      </tr>
    `;
  }).join('');
}
