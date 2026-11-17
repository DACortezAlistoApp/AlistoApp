// ============================================================================
// ALISTO NETWORK PH - AUTHENTICATED RBAC MASTER COMMAND & PROFILE CONSOLE
// Author & Chief Systems Architect: D. A. Cortez
// ============================================================================

const _K_U = atob("aHR0cHM6Ly9mbWpqeWhtdGRzdmh1ZmtneWpwci5zdXBhYmFzZS5jbw==");
const _K_A = atob("ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBjM01pT2lKemRYQmhZbUZ6WlNJc0luSmxaaUk2SW1adGFtcDVhRzEwWkhOMmFIVm1hMmQ1YW5CeUlpd2ljbTlzWlNJNkltRnViMjRpTENKcFlYUWlPakUzT1RRNU1URTVPRElzSW1WNGNDSTZNakV4TURRNE56azRNbjAueGV2a21NQkFuSVVjTktrcDM0UFRubWxrLTM4NVNVd3B3aGJWMEtheWd6cw==");
let supabaseClient = null;
let currentAuthUser = null;
let activeProfileModalUserId = null;
const SESSION_STORAGE_KEY = 'ALISTO_AUTH_SESSION_V1';

const consoleState = {
  globalCommissionRate: 0.12,
  maturityHours: 72,
  verticals: [],
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
    const { data, error } = await supabaseClient.rpc('authenticate_user_rpc', {
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
  const tabLgu = document.getElementById('tabBtnLguAdmin');
  const tabApk = document.getElementById('tabBtnApkPortal');
  const onbAdminOpt = document.getElementById('onbAdminOption');

  if (role === 'super_admin') {
    tabOp.style.display = 'inline-block';
    tabSuper.style.display = 'inline-block';
    tabLgu.style.display = 'inline-block';
    tabApk.style.display = 'inline-block';
    if (onbAdminOpt) onbAdminOpt.disabled = false;
    switchConsoleView('operator_ops');
  } else if (role === 'admin') {
    // Admin Operator: Can lookup/manage users & set manual user commission rates, but CANNOT access SuperAdmin Console (Verticals / Global Commission)
    tabOp.style.display = 'inline-block';
    tabSuper.style.display = 'none';
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
    tabLgu.style.display = 'none';
    tabApk.style.display = 'inline-block';
    switchConsoleView('apk_portal');
  }
}

function logoutConsoleSession() {
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

  if (viewKey === 'superadmin' && role !== 'super_admin') {
    alert('RBAC Restriction: Only SuperAdmin can enable/disable Service Verticals and set Global Commission Rates.');
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
  if (!supabaseClient || !currentAuthUser) return;
  try {
    const [
      vRes, cfgRes, uRes, bRes, lRes, ratRes, mRes, tRes, rRes, aRes, opRes
    ] = await Promise.all([
      supabaseClient.from('service_verticals').select('*').order('vertical_key'),
      supabaseClient.from('system_config').select('*'),
      supabaseClient.from('users').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('bookings').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('credit_ledger').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('booking_ratings').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('booking_messages').select('*').order('sent_at', { ascending: true }),
      supabaseClient.from('booking_telemetry').select('*').order('recorded_at', { ascending: false }),
      supabaseClient.from('moderation_rules').select('*').order('created_at'),
      supabaseClient.from('moderation_audit_logs').select('*').order('logged_at', { ascending: false }),
      supabaseClient.from('operator_audit_logs').select('*').order('created_at', { ascending: false })
    ]);

    if (vRes.data) consoleState.verticals = vRes.data;
    if (cfgRes.data) {
      const commRow = cfgRes.data.find(c => c.config_key === 'platform_commission_rate');
      const matRow = cfgRes.data.find(c => c.config_key === 'skills_maturity_window_hours');
      if (commRow) {
        consoleState.globalCommissionRate = parseFloat(commRow.config_value) || 0.12;
        const pct = +(consoleState.globalCommissionRate * 100).toFixed(2);
        if (document.getElementById('cfgCommission')) document.getElementById('cfgCommission').value = pct;
      }
      if (matRow) {
        consoleState.maturityHours = parseInt(matRow.config_value, 10) || 72;
        if (document.getElementById('cfgMaturity')) document.getElementById('cfgMaturity').value = consoleState.maturityHours;
      }
    }
    if (uRes.data) {
      consoleState.users = uRes.data;
      const freshSelf = consoleState.users.find(u => u.id === currentAuthUser.id);
      if (freshSelf && freshSelf.account_frozen) {
        alert('Your account has been suspended. Signing out.');
        logoutConsoleSession();
        return;
      }
    }
    if (bRes.data) consoleState.bookings = bRes.data;
    if (lRes.data) consoleState.ledger = lRes.data;
    if (ratRes.data) consoleState.ratings = ratRes.data;
    if (mRes.data) consoleState.messages = mRes.data;
    if (tRes.data) consoleState.telemetry = tRes.data;
    if (rRes.data) consoleState.moderationRules = rRes.data;
    if (aRes.data) consoleState.moderationAuditLogs = aRes.data;
    if (opRes.data) consoleState.operatorAuditLogs = opRes.data;
  } catch (err) {
    console.warn('Kernel sync notice:', err);
  }
  renderAll();
  if (activeProfileModalUserId && document.getElementById('profileModal').style.display === 'flex') {
    openProfileInspector(activeProfileModalUserId);
  }
}

let realtimeSubscribed = false;
function subscribeRealtimeKernel() {
  if (!supabaseClient || realtimeSubscribed) return;
  realtimeSubscribed = true;
  supabaseClient
    .channel('alisto-operator-live')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'service_verticals' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'users' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'bookings' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'credit_ledger' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'booking_ratings' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'booking_messages' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'booking_telemetry' }, () => syncFromSupabase())
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
      <span>${v.is_enabled ? 'ENABLED ON KOTLIN APKs' : 'DISABLED (SUPERADMIN OFF)'}</span>
    </div>
  `).join('');
}

// ============================================================================
// 3. USERNAME & FIRST/LAST NAME COMBO LOOKUP + ONBOARDING + COMMISSION
// ============================================================================

function toggleOnboardContractorFields() {
  const role = document.getElementById('onbRole').value;
  const sec = document.getElementById('onbContractorSection');
  if (sec) sec.style.display = role === 'contractor' ? 'block' : 'none';
}

function renderOperatorKpis() {
  const strip = document.getElementById('operatorKpiStrip');
  if (!strip) return;
  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  const customers = consoleState.users.filter(u => u.role === 'customer');
  const suspended = consoleState.users.filter(u => u.account_frozen);
  const activeJobs = consoleState.bookings.filter(b => ['SEARCHING', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS'].includes(b.status));
  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);

  strip.innerHTML = `
    <div class="kpi-card">
      <div class="kpi-label">Onboarded Contractors</div>
      <div class="kpi-value">${contractors.length}</div>
      <div class="kpi-sub">${contractors.filter(c => c.lgu_peso_endorsed).length} LGU/PESO Endorsed</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Onboarded Customers</div>
      <div class="kpi-value">${customers.length}</div>
      <div class="kpi-sub">Households & Businesses</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Total Bookings (All States)</div>
      <div class="kpi-value">${consoleState.bookings.length}</div>
      <div class="kpi-sub">${activeJobs.length} Requested / Active In-Field</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Global Default Commission</div>
      <div class="kpi-value">${globalPct}%</div>
      <div class="kpi-sub">SuperAdmin Controlled</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Suspended / Flagged</div>
      <div class="kpi-value" style="color:${suspended.length > 0 ? 'var(--crimson-err)' : 'var(--emerald-ok)'}">${suspended.length}</div>
      <div class="kpi-sub">${consoleState.moderationAuditLogs.length} Blocked Chat Infractions</div>
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
    if (roleFilter === 'FROZEN' && !u.account_frozen) return false;
    if (roleFilter !== 'ALL' && roleFilter !== 'FROZEN' && u.role !== roleFilter) return false;
    if (!cleanQuery) return true;

    const { firstName, lastName } = splitFirstLastName(u);
    const uname = (u.username || '').toLowerCase();
    const fullCombo = `${firstName} ${lastName}`.toLowerCase().trim();
    const revCombo = `${lastName} ${firstName}`.toLowerCase().trim();
    const fullName = (u.full_name || '').toLowerCase();
    const phone = (u.phone || '').toLowerCase();

    // Match by username OR First/Last Name combo (or individual tokens)
    const tokens = cleanQuery.split(/\s+/).filter(Boolean);
    const tokenMatch = tokens.every(t =>
      uname.includes(t) ||
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
      ? `Showing ${filtered.length} matching user(s) for query "${rawQuery}" — Click any @username below to open their full Profile Command Modal:`
      : `Showing all ${filtered.length} registered user account(s) — Click any @username below to open their full Profile Command Modal:`;
  }

  tbody.innerHTML = filtered.map(u => {
    const { firstName, lastName } = splitFirstLastName(u);
    const infractions = getInfractionCount(u.id);
    const certs = Array.isArray(u.certifications_docs) ? u.certifications_docs : [];
    const userRatings = consoleState.ratings.filter(r => r.ratee_id === u.id);
    const avgStars = userRatings.length > 0
      ? (userRatings.reduce((acc, r) => acc + Number(r.stars || 5), 0) / userRatings.length).toFixed(2)
      : Number(u.rating_avg || 5.0).toFixed(2);
    const ratingCnt = userRatings.length > 0 ? userRatings.length : Number(u.rating_count || 0);

    const commText = u.role === 'contractor'
      ? (u.custom_commission_rate != null
          ? `<strong style="color:var(--amber-core)">${(Number(u.custom_commission_rate) * 100).toFixed(1)}% (Manual Override)</strong>`
          : `${globalPct}% (Global Default)`)
      : '0% (Customer/Staff)';

    const statusHtml = u.account_frozen
      ? `<span class="pill-risk">SUSPENDED</span><br/><small style="color:#fca5a5">${u.suspension_reason || 'Rule Violation'}</small>`
      : `<span class="pill-clean">ACTIVE</span>${u.role === 'contractor' ? `<br/><small>${u.lgu_peso_endorsed ? 'LGU Endorsed' : 'Pending LGU'}</small>` : ''}`;

    return `
      <tr>
        <td>
          <button type="button" class="username-link" onclick="openProfileInspector('${u.id}')" title="Click to open Full User Profile & Governance Modal">
            @${u.username || u.role}
          </button>
        </td>
        <td>
          <strong>${firstName} ${lastName}</strong><br/>
          <small style="color:var(--text-muted)">${u.full_name}</small> • <span class="engine-tag">${u.role.toUpperCase()}</span>
        </td>
        <td>
          <code>${u.phone}</code><br/>
          <small style="color:var(--text-muted)">${u.barangay_zone}, ${u.municipality || 'Angeles City'}</small>
        </td>
        <td>
          Wallet: <strong style="color:var(--emerald-ok)">PHP ${Number(u.credit_balance || 0).toFixed(2)}</strong>
          ${u.role === 'contractor' ? `<small style="color:var(--amber-core)">(Held: PHP ${Number(u.held_credits || 0).toFixed(2)})</small>` : ''}<br/>
          <small>Commission: ${commText}</small>
        </td>
        <td>
          ${u.role === 'contractor'
            ? `<span class="csat-badge">★ ${avgStars} CSAT (${ratingCnt})</span> • <small>${certs.length} Cert(s)</small><br/>`
            : ''}
          <small style="color:${infractions > 0 ? '#fca5a5' : 'var(--text-muted)'}">Chat Flags: ${infractions}</small>
        </td>
        <td>${statusHtml}</td>
        <td>
          <button class="btn-xs" onclick="openProfileInspector('${u.id}')">
            Open Profile & Controls
          </button>
        </td>
      </tr>
    `;
  }).join('');
}

function renderOperatorContractorCommissionTable() {
  const tbody = document.getElementById('operatorContractorCommTable');
  const globalDisp = document.getElementById('opGlobalCommReadonlyDisplay');
  const jumpWrap = document.getElementById('opSuperAdminJumpWrap');
  if (!tbody || !currentAuthUser) return;

  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);
  if (globalDisp) globalDisp.textContent = `${globalPct}% (Default)`;

  if (jumpWrap) {
    if (currentAuthUser.role === 'super_admin') {
      jumpWrap.innerHTML = `<button class="btn-outline" onclick="switchConsoleView('superadmin')">Edit Global Rate in SuperAdmin Console →</button>`;
    } else {
      jumpWrap.innerHTML = `<span class="engine-tag">SUPERADMIN ONLY (READ-ONLY FOR ADMIN)</span>`;
    }
  }

  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  tbody.innerHTML = contractors.map(c => {
    const hasCustom = c.custom_commission_rate !== null && c.custom_commission_rate !== undefined;
    const effPct = hasCustom
      ? `${(Number(c.custom_commission_rate) * 100).toFixed(1)}% (Manual User Rate)`
      : `${globalPct}% (Global Default)`;
    const userRatings = consoleState.ratings.filter(r => r.ratee_id === c.id);
    const avgStars = userRatings.length > 0
      ? (userRatings.reduce((acc, r) => acc + Number(r.stars || 5), 0) / userRatings.length).toFixed(2)
      : Number(c.rating_avg || 5.0).toFixed(2);

    return `
      <tr>
        <td>
          <button type="button" class="username-link" onclick="openProfileInspector('${c.id}')">@${c.username || 'contractor'}</button><br/>
          <small>${c.full_name}</small>
        </td>
        <td>
          <strong style="color:var(--emerald-ok)">PHP ${Number(c.credit_balance || 0).toFixed(2)}</strong><br/>
          <small style="color:var(--amber-core)">Held: PHP ${Number(c.held_credits || 0).toFixed(2)}</small>
        </td>
        <td><span class="csat-badge">★ ${avgStars}</span></td>
        <td><span class="engine-tag">${effPct}</span></td>
        <td>
          <div class="action-cluster">
            <button class="btn-xs" onclick="promptContractorCommissionOverride('${c.id}', '${c.full_name.replace(/'/g, "")}')">
              Set Manual %
            </button>
            <button class="btn-xs" onclick="openProfileInspector('${c.id}')">
              Wallet / Profile
            </button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function operatorOnboardUser() {
  if (!currentAuthUser) return;
  const role = document.getElementById('onbRole').value;
  const username = document.getElementById('onbUsername').value.trim();
  const firstName = document.getElementById('onbFirstName').value.trim();
  const lastName = document.getElementById('onbLastName').value.trim();
  const fullName = `${firstName} ${lastName}`.trim();
  const phone = document.getElementById('onbPhone').value.trim();
  const email = document.getElementById('onbEmail').value.trim();
  const municipality = document.getElementById('onbMunicipality').value;
  const barangay = document.getElementById('onbBarangay').value;
  const password = document.getElementById('onbPassword').value.trim() || 'Alisto2026!';

  if (!firstName || !phone) {
    alert('First Name and Mobile Phone Number are required to onboard an account.');
    return;
  }

  const skillsRaw = document.getElementById('onbSkills').value;
  const skillArr = role === 'contractor'
    ? skillsRaw.split(',').map(s => s.trim()).filter(Boolean)
    : [];
  const lguEndorsed = role === 'contractor' ? (document.getElementById('onbLguEndorsed').value === 'true') : false;
  const initCredits = role === 'contractor' ? (parseFloat(document.getElementById('onbCredits').value) || 0) : 0;
  const customCommRaw = document.getElementById('onbCustomComm').value.trim();
  const customCommDecimal = (role === 'contractor' && customCommRaw !== '')
    ? (parseFloat(customCommRaw) / 100.0)
    : null;

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
    p_lgu_endorsed: lguEndorsed,
    p_skill_categories: skillArr,
    p_sub_400cc: true,
    p_initial_credits: initCredits,
    p_custom_commission_rate: customCommDecimal,
    p_username: username || null
  });

  if (error || (data && data.status === 'ERROR')) {
    alert('Onboarding Error: ' + (error ? error.message : data.message));
    return;
  }

  // Sync explicit first_name & last_name on the newly created record
  if (data && data.user && data.user.id) {
    await supabaseClient.rpc('operator_update_user_profile_rpc', {
      p_operator_id: currentAuthUser.id,
      p_target_user_id: data.user.id,
      p_username: data.user.username,
      p_first_name: firstName,
      p_last_name: lastName,
      p_full_name: fullName,
      p_phone: phone,
      p_email: email,
      p_municipality: municipality,
      p_barangay_zone: barangay,
      p_kyc_verified: true,
      p_lgu_endorsed: lguEndorsed,
      p_skill_categories: skillArr,
      p_certifications_docs: []
    });
  }

  document.getElementById('onbUsername').value = '';
  document.getElementById('onbFirstName').value = '';
  document.getElementById('onbLastName').value = '';
  document.getElementById('onbPhone').value = '';
  document.getElementById('onbEmail').value = '';
  alert(`Successfully onboarded ${role.toUpperCase()}: ${fullName} (Username: @${data.user.username})`);
  await syncFromSupabase();
}

async function promptContractorCommissionOverride(contractorId, contractorName) {
  if (!currentAuthUser) return;
  const input = prompt(
    `Set Manual Commission Rate (%) for ${contractorName} (e.g., enter 8 for 8%, or leave blank to restore Global Default):`,
    ''
  );
  if (input === null) return;
  const trimmed = input.trim();
  const decimalVal = trimmed === '' ? null : (parseFloat(trimmed) / 100.0);

  if (decimalVal !== null && (isNaN(decimalVal) || decimalVal < 0 || decimalVal > 0.5)) {
    alert('Invalid rate. Enter a percentage between 0 and 50.');
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
// 4. UNIFIED USER PROFILE & GOVERNANCE COMMAND MODAL (CLICKING @USERNAME)
// ============================================================================

function openProfileInspector(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  activeProfileModalUserId = u.id;

  const { firstName, lastName } = splitFirstLastName(u);
  const actorIsSuper = currentAuthUser.role === 'super_admin';
  const isTargetSuper = u.role === 'super_admin';
  const isTargetAdmin = u.role === 'admin';
  const canEditTarget = actorIsSuper || !isTargetSuper;
  const canSuspendTarget = !isTargetSuper && (actorIsSuper || !isTargetAdmin);

  const userBookings = consoleState.bookings.filter(b => b.client_id === u.id || b.contractor_id === u.id);
  const userLedger = consoleState.ledger.filter(l => l.user_id === u.id);
  const userRatings = consoleState.ratings.filter(r => r.ratee_id === u.id);
  const certs = Array.isArray(u.certifications_docs) ? u.certifications_docs : [];

  const avgStars = userRatings.length > 0
    ? (userRatings.reduce((acc, r) => acc + Number(r.stars || 5), 0) / userRatings.length).toFixed(2)
    : Number(u.rating_avg || 5.0).toFixed(2);
  const ratingCnt = userRatings.length > 0 ? userRatings.length : Number(u.rating_count || 0);

  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);
  const manualCommVal = u.custom_commission_rate != null ? (Number(u.custom_commission_rate) * 100).toFixed(1) : '';

  document.getElementById('profileModalTitle').innerHTML =
    `User Profile Command • <span style="color:var(--cyan-accent)">@${u.username || u.role}</span> — ${u.full_name} [${u.role.toUpperCase()}]`;

  // Scanned Certificates & Licenses Gallery HTML
  const certsHtml = certs.length === 0
    ? `<div style="color:var(--text-muted);padding:0.4rem 0;">No scanned certificates or licenses attached yet. Upload an image below.</div>`
    : `<div class="cert-grid">` + certs.map((c, idx) => `
        <div class="cert-card">
          <img src="${c.image_data_url}" alt="${c.title}" onclick="previewCertImage('${u.id}', ${idx})" title="Click to enlarge scanned certificate" />
          <div style="font-weight:700;font-size:0.78rem;color:var(--cyan-accent);">${c.title || 'Trade Certificate'}</div>
          <div style="font-size:0.72rem;color:var(--text-muted);">
            License/Cert #: <code>${c.license_no || 'N/A'}</code><br/>
            Issued By: ${c.issued_by || 'LGU / TESDA'} • ${c.uploaded_at || ''}
          </div>
          ${canEditTarget ? `<button class="btn-danger" style="margin-top:auto;" onclick="removeUserCertification('${u.id}', ${idx})">Remove Cert</button>` : ''}
        </div>
      `).join('') + `</div>`;

  // Contractor CSAT Section HTML
  const csatSectionHtml = u.role === 'contractor' ? `
    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;margin-bottom:0.6rem;">
        <h4>4. Contractor CSAT (Customer Satisfaction & Quality Scorecard — Engine 07)</h4>
        <div>
          <span class="csat-badge" style="font-size:0.88rem;padding:0.25rem 0.65rem;">
            ★ ${avgStars} / 5.00 CSAT (${ratingCnt} Verified Review${ratingCnt === 1 ? '' : 's'})
          </span>
          <button class="btn-xs" style="margin-left:0.4rem;" onclick="promptAddTestCsatReview('${u.id}')">+ Log CSAT Review</button>
        </div>
      </div>
      <div style="max-height:190px;overflow-y:auto;">
        ${userRatings.length === 0
          ? `<div style="color:var(--text-muted)">No itemized CSAT reviews recorded yet.</div>`
          : userRatings.map(r => `
              <div style="padding:0.5rem;margin-bottom:0.45rem;background:#090d16;border:1px solid var(--border-subtle);border-radius:6px;">
                <div style="display:flex;justify-content:space-between;">
                  <strong style="color:var(--amber-core)">${'★'.repeat(r.stars)}${'☆'.repeat(5 - r.stars)} (${r.stars}/5)</strong>
                  <span style="font-size:0.72rem;color:var(--text-muted)">Reviewer: ${getUserLabel(r.rater_id)} • ${formatTs(r.created_at)}</span>
                </div>
                <div style="margin:0.25rem 0;">
                  ${(r.feedback_tags || []).map(t => `<span class="engine-tag" style="margin-right:0.3rem;font-size:0.66rem;">${t}</span>`).join('')}
                </div>
                <div style="color:var(--text-main);font-size:0.78rem;">"${r.comments || 'Verified satisfactory service completion.'}"</div>
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
      <!-- PANEL 1: EDIT PROFILE DETAILS & SKILLS -->
      <div class="forensic-box">
        <h4>1. Update Profile Details, Phone & Skills (\`@${u.username || u.role}\`)</h4>
        <div class="profile-input-grid">
          <div>
            <label>Username (\`@username\`)</label>
            <input type="text" id="profUsername" value="${u.username || ''}" ${!canEditTarget ? 'disabled' : ''} />
          </div>
          <div>
            <label>Mobile Phone Number</label>
            <input type="text" id="profPhone" value="${u.phone || ''}" ${!canEditTarget ? 'disabled' : ''} />
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
        <div style="margin-top:0.55rem;">
          <label style="display:block;font-size:0.74rem;color:var(--text-muted);margin-bottom:0.2rem;">
            Skills & Trade Categories (Comma-Separated — Add or Edit Skills)
          </label>
          <input type="text" id="profSkills" value="${(u.skill_categories || []).join(', ')}"
                 placeholder="e.g., Aircon & HVAC, Electrical Repair, Plumbing, Sub-400cc Delivery"
                 style="width:100%;padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);"
                 ${!canEditTarget ? 'disabled' : ''} />
        </div>
        ${canEditTarget
          ? `<button class="btn-primary" style="margin-top:0.7rem;width:100%;" onclick="saveProfileModalChanges('${u.id}')">
               Save Profile, Phone & Skills Updates
             </button>`
          : `<div style="margin-top:0.6rem;color:var(--text-muted);font-size:0.75rem;">SuperAdmin profile is read-only for Admin Operators.</div>`}
      </div>

      <!-- PANEL 2: PASSWORD RESET, MANUAL SUSPENSION & MANUAL COMMISSION RATE -->
      <div class="forensic-box">
        <h4>2. Security, Manual Suspension & User Commission Rate</h4>

        <!-- Password Reset -->
        <div style="padding-bottom:0.65rem;margin-bottom:0.65rem;border-bottom:1px solid var(--border-subtle);">
          <label style="display:block;font-size:0.75rem;color:var(--text-muted);margin-bottom:0.25rem;">
            Reset User Password (\`public.user_credentials\`)
          </label>
          <div style="display:grid;grid-template-columns:1fr auto;gap:0.45rem;">
            <input type="text" id="profNewPasswordInput" placeholder="Enter new password (e.g. Alisto2026!)"
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
            <span style="font-size:0.76rem;color:var(--text-muted);">Current Account Standing:</span>
            ${u.account_frozen
              ? `<span class="pill-risk">SUSPENDED (${u.suspension_reason || 'Policy Violation'})</span>`
              : `<span class="pill-clean">ACTIVE & IN GOOD STANDING</span>`}
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
                 </button>`
              : `<button class="btn-danger" onclick="saveProfileSuspensionToggle('${u.id}', true)" ${!canSuspendTarget ? 'disabled' : ''}>
                   Suspend User Manually
                 </button>`}
          </div>
        </div>

        <!-- Manual Commission Rate of Specific User (SuperAdmin & Admin) -->
        <div>
          <label style="display:block;font-size:0.75rem;color:var(--text-muted);margin-bottom:0.25rem;">
            Manual Commission Rate for This User (% — Global Default is ${globalPct}%)
          </label>
          <div style="display:grid;grid-template-columns:1fr auto auto;gap:0.45rem;">
            <input type="number" id="profManualCommInput" value="${manualCommVal}"
                   placeholder="Global Default (${globalPct}%)" min="0" max="50" step="0.5"
                   style="padding:0.45rem 0.6rem;border-radius:6px;border:1px solid var(--border-subtle);background:#090d16;color:var(--text-main);" />
            <button class="btn-primary" onclick="saveProfileManualCommission('${u.id}', false)">
              Set User Rate
            </button>
            <button class="btn-outline" onclick="saveProfileManualCommission('${u.id}', true)">
              Use Default
            </button>
          </div>
          <small style="color:var(--text-muted);font-size:0.7rem;">
            Both <code>super_admin</code> and <code>admin</code> can set a manual commission rate for a specific user.
          </small>
        </div>
      </div>
    </div>

    <!-- PANEL 3: SCANNED CERTIFICATES & LICENSES VAULT -->
    <div class="forensic-box">
      <h4>3. Scanned Certificates, Permits & Licenses Vault (${certs.length} Attached)</h4>
      <div class="profile-input-grid" style="grid-template-columns: 1.2fr 1fr 1fr 1.2fr auto; align-items: end;">
        <div>
          <label>Certificate / License Title</label>
          <input type="text" id="certTitleInput" placeholder="e.g., TESDA NC-II HVAC / Driver License" />
        </div>
        <div>
          <label>License / Cert Number</label>
          <input type="text" id="certNumberInput" placeholder="e.g., TESDA-2026-9912" />
        </div>
        <div>
          <label>Issuing Authority</label>
          <input type="text" id="certIssuerInput" placeholder="e.g., TESDA / LTO / PRC" />
        </div>
        <div>
          <label>Select Scanned Image File</label>
          <input type="file" id="certFileInput" accept="image/*" />
        </div>
        <div>
          <button class="btn-primary" onclick="uploadUserCertificationImage('${u.id}')" ${!canEditTarget ? 'disabled' : ''}>
            + Attach Scanned Cert
          </button>
        </div>
      </div>
      ${certsHtml}
    </div>

    <!-- PANEL 4: CONTRACTOR CSAT SCORECARD -->
    ${csatSectionHtml}

    <!-- PANEL 5: WALLET CREDIT / DEBIT CONSOLE & LEDGER HISTORY -->
    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:0.5rem;margin-bottom:0.65rem;">
        <h4>5. Prepaid Wallet Credit / Debit Console & Immutable Ledger (\`public.credit_ledger\`)</h4>
        <div>
          <span style="font-size:0.85rem;margin-right:0.8rem;">
            Available Balance: <strong style="color:var(--emerald-ok);font-size:1.05rem;">PHP ${Number(u.credit_balance || 0).toFixed(2)}</strong>
          </span>
          <span style="font-size:0.82rem;color:var(--amber-core);">
            Locked Commission Holds: <strong>PHP ${Number(u.held_credits || 0).toFixed(2)}</strong>
          </span>
        </div>
      </div>

      <div class="profile-input-grid" style="grid-template-columns: 190px 160px 1fr auto; align-items: end; margin-bottom: 0.75rem;">
        <div>
          <label>Adjustment Operation</label>
          <select id="walletAdjDirection">
            <option value="CREDIT">CREDIT (+ Add to Wallet)</option>
            <option value="DEBIT">DEBIT (- Deduct from Wallet)</option>
          </select>
        </div>
        <div>
          <label>Amount (PHP)</label>
          <input type="number" id="walletAdjAmount" value="500" min="1" step="50" />
        </div>
        <div>
          <label>Audit Reference / Justification Note</label>
          <input type="text" id="walletAdjReason" placeholder="e.g., PayMongo QR Ph OTC deposit / Penalty debit adjustment" />
        </div>
        <div>
          <button class="btn-primary" onclick="executeProfileWalletAdjustment('${u.id}')">
            Execute Wallet Credit / Debit
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

  alert(`Profile details & skills updated for @${username} (${fullName}).`);
  await syncFromSupabase();
}

async function saveProfilePasswordReset(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;
  const newPw = document.getElementById('profNewPasswordInput').value.trim();
  if (!newPw) {
    alert('Enter the new password in the password field first.');
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
  alert(`Password successfully reset for @${u.username || u.full_name}.`);
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
    if (raw === '') {
      decimalVal = null;
    } else {
      const pct = parseFloat(raw);
      if (isNaN(pct) || pct < 0 || pct > 50) {
        alert('Enter a valid commission rate between 0% and 50%.');
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
    alert('Enter a valid adjustment amount greater than PHP 0.00.');
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

function compressImageFileToDataUrl(file, maxWidth = 680) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = e => {
      const img = new Image();
      img.onload = () => {
        const scale = Math.min(1, maxWidth / img.width);
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        const ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL('image/jpeg', 0.82));
      };
      img.onerror = reject;
      img.src = e.target.result;
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

async function uploadUserCertificationImage(userId) {
  const u = getUserById(userId);
  if (!u || !currentAuthUser) return;

  const title = document.getElementById('certTitleInput').value.trim() || 'Trade Certificate / License';
  const licNo = document.getElementById('certNumberInput').value.trim() || 'VERIFIED-DOC';
  const issuer = document.getElementById('certIssuerInput').value.trim() || 'LGU / TESDA';
  const fileInput = document.getElementById('certFileInput');

  let imageDataUrl = '';
  if (fileInput && fileInput.files && fileInput.files[0]) {
    imageDataUrl = await compressImageFileToDataUrl(fileInput.files[0]);
  } else {
    // Generate a clean digital certificate badge if no file was selected
    imageDataUrl = `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300" viewBox="0 0 480 300"><rect width="480" height="300" fill="%230f172a" stroke="%2334d399" stroke-width="6"/><text x="240" y="65" fill="%23fbbf24" font-family="sans-serif" font-size="18" font-weight="bold" text-anchor="middle">${encodeURIComponent(issuer.toUpperCase())}</text><text x="240" y="115" fill="%23ffffff" font-family="sans-serif" font-size="18" font-weight="bold" text-anchor="middle">${encodeURIComponent(title)}</text><text x="240" y="160" fill="%2300f2fe" font-family="sans-serif" font-size="15" text-anchor="middle">Holder: ${encodeURIComponent(u.full_name)} (@${encodeURIComponent(u.username || '')})</text><text x="240" y="205" fill="%2334d399" font-family="sans-serif" font-size="14" font-weight="bold" text-anchor="middle">License / Cert #: ${encodeURIComponent(licNo)}</text></svg>`;
  }

  const existingCerts = Array.isArray(u.certifications_docs) ? [...u.certifications_docs] : [];
  existingCerts.unshift({
    id: 'cert_' + Date.now(),
    title: title,
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
    alert('Certificate Upload Error: ' + (error ? error.message : data.message));
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
  const w = window.open('', '_blank', 'width=720,height=520');
  if (w) {
    w.document.write(`<html><head><title>${c.title}</title></head><body style="margin:0;background:#070a12;color:#fff;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:100vh;padding:20px;"><h3 style="color:#00f2fe;margin-bottom:8px;">${c.title} (${c.license_no})</h3><p style="color:#94a3b8;margin-top:0;">Issued By: ${c.issued_by} • Holder: ${u.full_name} (@${u.username})</p><img src="${c.image_data_url}" style="max-width:95%;max-height:75vh;border:2px solid #00f2fe;border-radius:8px;" /></body></html>`);
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
// 5. BOOKINGS TABLE & FORENSIC INVESTIGATION DOSSIER
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
          <small><strong>Client:</strong> ${clientObj ? `<a class="username-link" onclick="openProfileInspector('${clientObj.id}')">@${clientObj.username}</a> ${clientObj.full_name}` : b.client_id}</small><br/>
          <small><strong>Contractor:</strong> ${contObj ? `<a class="username-link" onclick="openProfileInspector('${contObj.id}')">@${contObj.username}</a> ${contObj.full_name}` : 'Unclaimed (On Board)'}</small>
        </td>
        <td>
          Fee: <strong>PHP ${Number(b.quoted_fee).toFixed(2)}</strong><br/>
          <small>Tip: PHP ${Number(b.tip_amount || 0).toFixed(2)} | Hold: PHP ${Number(b.commission_locked || 0).toFixed(2)}</small>
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
          <span class="pill-clean">${g.sub_400cc_compliant ? 'Sub-400cc Non-Expressway OK' : 'EXPRESSWAY ALERT'}</span>
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
          <li><strong>Customer:</strong> ${client ? `<a class="username-link" onclick="closeForensicModal();openProfileInspector('${client.id}')">@${client.username}</a> ${client.full_name}` : b.client_id}</li>
          <li><strong>Contractor:</strong> ${contractor ? `<a class="username-link" onclick="closeForensicModal();openProfileInspector('${contractor.id}')">@${contractor.username}</a> ${contractor.full_name}` : 'Unassigned'}</li>
          <li><strong>Pickup / Site Address:</strong> ${b.pickup_address} (${b.barangay_zone}, ${b.municipality})</li>
          <li><strong>Pickup Coords:</strong> <code>${b.pickup_lat || 15.1450}, ${b.pickup_lng || 120.5887}</code></li>
          <li><strong>Quoted Service Fee:</strong> PHP ${Number(b.quoted_fee).toFixed(2)} (+PHP ${Number(b.tip_amount || 0).toFixed(2)} Tip)</li>
          <li><strong>Locked Commission Hold:</strong> PHP ${Number(b.commission_locked || 0).toFixed(2)}</li>
          <li><strong>Sub-400cc Route Rule:</strong> ${b.non_expressway_route_verified ? 'Verified Non-Expressway' : 'Pending'}</li>
        </ul>
      </div>
    </div>

    <div class="forensic-grid">
      <div class="forensic-box">
        <h4>3. Transactional In-App Chat History (<code>booking_messages</code>)</h4>
        <div style="max-height:200px;overflow-y:auto;">${chatHtml}</div>
      </div>

      <div class="forensic-box">
        <h4>4. Blocked Chat Violations (<code>moderation_audit_logs</code>)</h4>
        <div style="max-height:200px;overflow-y:auto;">${flagsHtml}</div>
      </div>
    </div>

    <div class="forensic-box">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:0.5rem;">
        <h4>5. Location & Real-Time GPS Telemetry Breadcrumbs (<code>booking_telemetry</code>)</h4>
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
// 6. SUPERADMIN EXCLUSIVE CONSOLE, LGU & SCOPED USER VIEWS
// ============================================================================

function renderSuperAdmin() {
  if (!currentAuthUser || currentAuthUser.role !== 'super_admin') return;

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
            ${v.is_enabled ? 'ACTIVE (Click to Turn OFF)' : 'OFF (Click to Turn ON)'}
          </button>
        </div>
      </div>
    `).join('');
  }

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
            <span style="color:${c.kyc_verified && c.lgu_peso_endorsed ? 'var(--emerald-ok)' : 'var(--crimson-err)'};font-weight:700;">
              ${c.kyc_verified && c.lgu_peso_endorsed ? 'KYC + LGU/PESO ENDORSED' : 'ENDORSEMENT SUSPENDED'}
            </span>
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
      <h2>Authenticated Account Session Profile • ${selfRecord.full_name} (@${selfRecord.username || selfRecord.role})</h2>
      <span class="engine-tag">ROLE: ${selfRecord.role.toUpperCase()}</span>
    </div>
    <div style="font-size:0.83rem;color:var(--text-muted);display:flex;gap:1.5rem;flex-wrap:wrap;">
      <div><strong>Phone:</strong> ${selfRecord.phone}</div>
      <div><strong>Zone:</strong> ${selfRecord.barangay_zone}, ${selfRecord.municipality}</div>
      ${selfRecord.role === 'contractor' ? `<div><strong>Prepaid Credits:</strong> <span style="color:var(--emerald-ok);font-weight:700;">PHP ${Number(selfRecord.credit_balance || 0).toFixed(2)}</span> (Held: PHP ${Number(selfRecord.held_credits || 0).toFixed(2)})</div>` : ''}
      <div><strong>Associated Bookings:</strong> ${myBookings.length}</div>
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
    renderOperatorContractorCommissionTable();
    renderOperatorBookingsTable();
    renderLguAdmin();
  }
  if (currentAuthUser.role === 'super_admin') {
    renderSuperAdmin();
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
