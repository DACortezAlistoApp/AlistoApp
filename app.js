// ============================================================================
// ALISTO NETWORK PH - AUTHENTICATED RBAC MASTER COMMAND CONSOLE
// Author & Chief Systems Architect: D. A. Cortez
// ============================================================================

const _K_U = atob("aHR0cHM6Ly9mbWpqeWhtdGRzdmh1ZmtneWpwci5zdXBhYmFzZS5jbw==");
const _K_A = atob("ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBjM01pT2lKemRYQmhZbUZ6WlNJc0luSmxaaUk2SW1adGFtcDVhRzEwWkhOMmFIVm1hMmQ1YW5CeUlpd2ljbTlzWlNJNkltRnViMjRpTENKcFlYUWlPakUzT1RRNU1URTVPRElzSW1WNGNDSTZNakV4TURRNE56azRNbjAueGV2a21NQkFuSVVjTktrcDM0UFRubWxrLTM4NVNVd3B3aGJWMEtheWd6cw==");
let supabaseClient = null;
let currentAuthUser = null;
const SESSION_STORAGE_KEY = 'ALISTO_AUTH_SESSION_V1';

const consoleState = {
  globalCommissionRate: 0.12,
  maturityHours: 72,
  verticals: [],
  users: [],
  bookings: [],
  ledger: [],
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
      errBanner.textContent = 'Database Auth RPC Error (Ensure 04_alisto_auth_and_rbac_lockdown.sql is executed in Supabase): ' + error.message;
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
    // SuperAdmin: Full unrestricted access to all 4 tabs & ability to onboard Admin Operators
    tabOp.style.display = 'inline-block';
    tabSuper.style.display = 'inline-block';
    tabLgu.style.display = 'inline-block';
    tabApk.style.display = 'inline-block';
    if (onbAdminOpt) onbAdminOpt.disabled = false;
    switchConsoleView('operator_ops');
  } else if (role === 'admin') {
    // Admin Operator / LGU Coordinator: Access to Operator Dashboard, LGU Audit Trail & APKs; SuperAdmin Console LOCKED
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
    // Contractor / Customer: Strictly isolated to their Personal Account & Native Android Kotlin APK Portal
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

  // Hard guard against unauthorized tab switching
  if (viewKey === 'superadmin' && role !== 'super_admin') {
    alert('RBAC Restriction: Only SuperAdmin can access the Master Service Vertical & Moderation Console.');
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
  return u ? `${u.full_name} (${u.phone})` : (uid ? uid.slice(0, 8) : 'Unassigned');
}

function getInfractionCount(uid) {
  return consoleState.moderationAuditLogs.filter(l => l.sender_id === uid).length;
}

async function syncFromSupabase() {
  if (!supabaseClient || !currentAuthUser) return;
  try {
    const [
      vRes, cfgRes, uRes, bRes, lRes, mRes, tRes, rRes, aRes, opRes
    ] = await Promise.all([
      supabaseClient.from('service_verticals').select('*').order('vertical_key'),
      supabaseClient.from('system_config').select('*'),
      supabaseClient.from('users').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('bookings').select('*').order('created_at', { ascending: false }),
      supabaseClient.from('credit_ledger').select('*').order('created_at', { ascending: false }),
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
        if (document.getElementById('opGlobalCommInput')) document.getElementById('opGlobalCommInput').value = pct;
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
        alert('Your account has been suspended by an administrator. Signing out.');
        logoutConsoleSession();
        return;
      }
    }
    if (bRes.data) consoleState.bookings = bRes.data;
    if (lRes.data) consoleState.ledger = lRes.data;
    if (mRes.data) consoleState.messages = mRes.data;
    if (tRes.data) consoleState.telemetry = tRes.data;
    if (rRes.data) consoleState.moderationRules = rRes.data;
    if (aRes.data) consoleState.moderationAuditLogs = aRes.data;
    if (opRes.data) consoleState.operatorAuditLogs = opRes.data;
  } catch (err) {
    console.warn('Kernel sync notice:', err);
  }
  renderAll();
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
// 3. OPERATOR DASHBOARD RENDERERS & ROLE-PROTECTED ACTIONS
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
      <div class="kpi-label">Default Commission Rate</div>
      <div class="kpi-value">${globalPct}%</div>
      <div class="kpi-sub">Locked via Prepaid Service Credits</div>
    </div>
    <div class="kpi-card">
      <div class="kpi-label">Suspended / Flagged</div>
      <div class="kpi-value" style="color:${suspended.length > 0 ? 'var(--crimson-err)' : 'var(--emerald-ok)'}">${suspended.length}</div>
      <div class="kpi-sub">${consoleState.moderationAuditLogs.length} Blocked Chat Infractions</div>
    </div>
  `;
}

async function operatorOnboardUser() {
  if (!currentAuthUser) return;
  const role = document.getElementById('onbRole').value;
  const username = document.getElementById('onbUsername').value.trim();
  const fullName = document.getElementById('onbName').value.trim();
  const phone = document.getElementById('onbPhone').value.trim();
  const email = document.getElementById('onbEmail').value.trim();
  const municipality = document.getElementById('onbMunicipality').value;
  const barangay = document.getElementById('onbBarangay').value;
  const password = document.getElementById('onbPassword').value.trim() || 'Alisto2026!';

  if (!fullName || !phone) {
    alert('Full Name and Mobile Phone Number are required to onboard an account.');
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

  document.getElementById('onbUsername').value = '';
  document.getElementById('onbName').value = '';
  document.getElementById('onbPhone').value = '';
  document.getElementById('onbEmail').value = '';
  alert(`Successfully onboarded ${role.toUpperCase()}: ${fullName} (Username: @${data.user.username})`);
  await syncFromSupabase();
}

async function operatorUpdateGlobalCommission() {
  if (!currentAuthUser) return;
  const pct = parseFloat(document.getElementById('opGlobalCommInput').value);
  if (isNaN(pct) || pct <= 0 || pct > 50) {
    alert('Enter a valid commission percentage between 1% and 50%.');
    return;
  }
  const rateDecimal = (pct / 100.0).toFixed(4);
  const { data, error } = await supabaseClient.rpc('admin_update_system_config_rpc', {
    p_commission_rate: String(rateDecimal),
    p_maturity_hours: String(consoleState.maturityHours),
    p_actor_id: currentAuthUser.id
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('RPC Error: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
  alert(`Global Platform Commission Rate updated to ${pct}%.`);
}

function renderOperatorContractorCommissionTable() {
  const tbody = document.getElementById('operatorContractorCommTable');
  if (!tbody) return;
  const contractors = consoleState.users.filter(u => u.role === 'contractor');
  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);

  tbody.innerHTML = contractors.map(c => {
    const hasCustom = c.custom_commission_rate !== null && c.custom_commission_rate !== undefined;
    const effPct = hasCustom
      ? `${(Number(c.custom_commission_rate) * 100).toFixed(1)}% (Custom Override)`
      : `${globalPct}% (Global Default)`;
    return `
      <tr>
        <td>
          <strong>${c.full_name}</strong> <small style="color:var(--cyan-accent)">@${c.username || 'contractor'}</small><br/>
          <small style="color:var(--text-muted)">${c.phone} • ${c.barangay_zone}</small>
        </td>
        <td>
          <strong style="color:var(--emerald-ok)">PHP ${Number(c.credit_balance || 0).toFixed(2)}</strong><br/>
          <small style="color:var(--amber-core)">Held: PHP ${Number(c.held_credits || 0).toFixed(2)}</small>
        </td>
        <td><span class="engine-tag">${effPct}</span></td>
        <td>
          <div class="action-cluster">
            <button class="btn-xs" onclick="promptContractorCommissionOverride('${c.id}', '${c.full_name.replace(/'/g, "")}')">
              Set Custom %
            </button>
            <button class="btn-xs" onclick="operatorTopUpContractor('${c.id}', '${c.full_name.replace(/'/g, "")}')">
              + Top Up Credits
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
    `Set Custom Commission Rate (%) for ${contractorName} (e.g., enter 8 for 8%, or leave blank to restore Global Default):`,
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
    alert('Error updating contractor commission: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
}

async function operatorTopUpContractor(contractorId, contractorName) {
  const amtStr = prompt(`Enter Prepaid Service Credit Top-Up amount (PHP) for ${contractorName} (Settled via PayMongo QR Ph / OTC):`, '500');
  if (!amtStr) return;
  const amount = parseFloat(amtStr);
  if (isNaN(amount) || amount <= 0) return;

  const { error } = await supabaseClient.rpc('topup_prepaid_credits_rpc', {
    p_user_id: contractorId,
    p_amount: amount,
    p_qrph_ref: 'OP-QRPH-' + Date.now()
  });
  if (error) {
    alert('Top-Up RPC Error: ' + error.message);
    return;
  }
  await syncFromSupabase();
}

function renderOperatorUsersTable() {
  const tbody = document.getElementById('operatorUsersTable');
  if (!tbody || !currentAuthUser) return;
  const q = (document.getElementById('userSearchInput')?.value || '').toLowerCase().trim();
  const roleFilter = document.getElementById('userRoleFilter')?.value || 'ALL';
  const globalPct = (consoleState.globalCommissionRate * 100).toFixed(1);
  const actorIsSuper = currentAuthUser.role === 'super_admin';

  const filtered = consoleState.users.filter(u => {
    if (roleFilter === 'FROZEN' && !u.account_frozen) return false;
    if (roleFilter !== 'ALL' && roleFilter !== 'FROZEN' && u.role !== roleFilter) return false;
    if (!q) return true;
    const hay = `${u.username || ''} ${u.full_name} ${u.phone} ${u.email || ''} ${u.barangay_zone} ${u.municipality} ${u.role}`.toLowerCase();
    return hay.includes(q);
  });

  tbody.innerHTML = filtered.map(u => {
    const infractions = getInfractionCount(u.id);
    let riskBadge = `<span class="pill-clean">CLEAN (0 Flags)</span>`;
    if (infractions >= 3) {
      riskBadge = `<span class="pill-risk">HIGH RISK (${infractions} Side-Deal Flags)</span>`;
    } else if (infractions >= 1) {
      riskBadge = `<span class="pill-watch">WATCHLIST (${infractions} Flagged)</span>`;
    }

    const commText = u.role === 'contractor'
      ? (u.custom_commission_rate != null
          ? `${(Number(u.custom_commission_rate) * 100).toFixed(1)}% (Custom)`
          : `${globalPct}% (Default)`)
      : u.role.toUpperCase();

    const statusHtml = u.account_frozen
      ? `<span class="pill-risk">SUSPENDED</span><br/><small style="color:#fca5a5">${u.suspension_reason || 'Rule Violation'}</small>`
      : `<span class="pill-clean">ACTIVE</span>${u.role === 'contractor' ? `<br/><small>${u.lgu_peso_endorsed ? 'LGU Endorsed' : 'No LGU Endorsement'}</small>` : ''}`;

    const safeName = (u.full_name || '').replace(/'/g, "");
    const isTargetSuper = u.role === 'super_admin';
    const isTargetAdmin = u.role === 'admin';

    // Role Hierarchy Enforcement in UI
    const canResetPw = actorIsSuper || !isTargetSuper;
    const canSuspend = !isTargetSuper && (actorIsSuper || !isTargetAdmin);

    return `
      <tr>
        <td>
          <strong>${u.full_name}</strong> <small style="color:var(--cyan-accent)">@${u.username || u.role}</small><br/>
          <span class="engine-tag">${u.role.toUpperCase()}</span>
        </td>
        <td>
          <code>${u.phone}</code><br/>
          <small style="color:var(--text-muted)">${u.email || '—'}</small>
        </td>
        <td>${u.barangay_zone}, ${u.municipality || 'Angeles City'}</td>
        <td>
          ${u.role === 'contractor'
            ? `Avail: <strong>PHP ${Number(u.credit_balance || 0).toFixed(2)}</strong><br/><small>Rate: ${commText}</small>`
            : `<small style="color:var(--text-muted)">${commText}</small>`}
        </td>
        <td>${riskBadge}</td>
        <td>${statusHtml}</td>
        <td>
          <div class="action-cluster">
            <button class="btn-xs" onclick="openProfileInspector('${u.id}')">View Profile</button>
            ${canResetPw
              ? `<button class="btn-xs" onclick="operatorResetPassword('${u.id}', '${safeName}')">Reset PW</button>`
              : `<span style="font-size:0.7rem;color:var(--text-muted)">SuperAdmin Locked</span>`}
            ${canSuspend
              ? (u.account_frozen
                  ? `<button class="btn-xs" style="border-color:var(--emerald-ok);color:var(--emerald-ok);" onclick="operatorToggleSuspension('${u.id}', false, '${safeName}')">Reinstate</button>`
                  : `<button class="btn-danger" onclick="operatorToggleSuspension('${u.id}', true, '${safeName}')">Suspend</button>`)
              : ''}
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function operatorResetPassword(userId, userName) {
  if (!currentAuthUser) return;
  const newPw = prompt(`Enter new password for ${userName}:`, 'AlistoReset2026!');
  if (!newPw) return;
  const { error, data } = await supabaseClient.rpc('operator_reset_user_password_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_new_password: newPw.trim()
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Password Reset Error: ' + (error ? error.message : data.message));
    return;
  }
  alert(`Password for ${userName} has been updated in public.user_credentials and logged.`);
  await syncFromSupabase();
}

async function operatorToggleSuspension(userId, suspend, userName) {
  if (!currentAuthUser) return;
  let reason = 'Cleared by Admin Operator';
  if (suspend) {
    reason = prompt(`Enter official violation reason for suspending ${userName}:`, 'Off-platform side-deal solicitation / Safety rule violation');
    if (reason === null) return;
  }
  const { error, data } = await supabaseClient.rpc('operator_set_account_suspension_rpc', {
    p_operator_id: currentAuthUser.id,
    p_target_user_id: userId,
    p_suspend: suspend,
    p_reason: reason
  });
  if (error || (data && data.status === 'ERROR')) {
    alert('Suspension RPC Error: ' + (error ? error.message : data.message));
    return;
  }
  await syncFromSupabase();
}

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
          <small><strong>Client:</strong> ${getUserLabel(b.client_id)}</small><br/>
          <small><strong>Contractor:</strong> ${b.contractor_id ? getUserLabel(b.contractor_id) : 'Unclaimed (On Board)'}</small>
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

// ============================================================================
// 4. FORENSIC BOOKING DOSSIER & PROFILE INSPECTOR MODALS
// ============================================================================

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
          <li><strong>Customer:</strong> ${client ? `${client.full_name} (${client.phone})` : b.client_id}</li>
          <li><strong>Contractor:</strong> ${contractor ? `${contractor.full_name} (${contractor.phone})` : 'Unassigned'}</li>
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
          ? `<button class="btn-danger" onclick="operatorToggleSuspension('${contractor.id}', true, '${contractor.full_name.replace(/'/g, "")}')">Suspend Contractor Account</button>`
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

function openProfileInspector(userId) {
  const u = getUserById(userId);
  if (!u) return;

  const userBookings = consoleState.bookings.filter(b => b.client_id === u.id || b.contractor_id === u.id);
  const userLedger = consoleState.ledger.filter(l => l.user_id === u.id);
  const userFlags = consoleState.moderationAuditLogs.filter(f => f.sender_id === u.id);

  document.getElementById('profileModalTitle').textContent =
    `Account Profile Inspector • ${u.full_name} (@${u.username || u.role}) [${u.role.toUpperCase()}]`;

  const bookingsRows = userBookings.length === 0
    ? `<div style="color:var(--text-muted)">No bookings associated with this account yet.</div>`
    : userBookings.map(b => `
        <div style="padding:0.4rem 0;border-bottom:1px solid var(--border-subtle);display:flex;justify-content:space-between;">
          <span><code>${b.booking_ref}</code> • <strong>${b.service_title}</strong> (${b.barangay_zone})</span>
          <span><span class="engine-tag">${b.status}</span> • PHP ${Number(b.quoted_fee).toFixed(2)}</span>
        </div>
      `).join('');

  const ledgerRows = userLedger.length === 0
    ? `<div style="color:var(--text-muted)">No credit ledger entries for this account.</div>`
    : userLedger.map(l => `
        <div style="padding:0.35rem 0;border-bottom:1px solid var(--border-subtle);font-size:0.76rem;">
          <strong>${formatTs(l.created_at)}</strong> • <code>${l.entry_type}</code> •
          Amount: <strong>PHP ${Number(l.amount).toFixed(2)}</strong> (Avail After: PHP ${Number(l.balance_after).toFixed(2)}) — ${l.notes || ''}
        </div>
      `).join('');

  const body = document.getElementById('profileModalBody');
  body.innerHTML = `
    <div class="forensic-grid">
      <div class="forensic-box">
        <h4>Identity, Governance & Credentials</h4>
        <ul class="ts-list">
          <li><strong>Account UUID:</strong> <code>${u.id}</code></li>
          <li><strong>Username:</strong> <code>@${u.username || '—'}</code></li>
          <li><strong>Full Name:</strong> ${u.full_name}</li>
          <li><strong>Role:</strong> ${u.role.toUpperCase()}</li>
          <li><strong>Phone / Email:</strong> ${u.phone} / ${u.email || '—'}</li>
          <li><strong>Barangay / Municipality:</strong> ${u.barangay_zone}, ${u.municipality}</li>
          <li><strong>KYC & LGU Status:</strong> KYC=${u.kyc_verified ? 'YES' : 'NO'} | LGU Endorsed=${u.lgu_peso_endorsed ? 'YES' : 'NO'}</li>
          <li><strong>Last Login / PW Update:</strong> ${formatTs(u.last_login_at || u.password_updated_at || u.created_at)}</li>
        </ul>
      </div>

      <div class="forensic-box">
        <h4>Prepaid Wallet, Commission Tier & Trust</h4>
        <ul class="ts-list">
          <li><strong>Available Prepaid Credits:</strong> PHP ${Number(u.credit_balance || 0).toFixed(2)}</li>
          <li><strong>Locked Commission Holds:</strong> PHP ${Number(u.held_credits || 0).toFixed(2)}</li>
          <li><strong>Custom Commission Override:</strong> ${u.custom_commission_rate != null ? (Number(u.custom_commission_rate) * 100).toFixed(1) + '%' : 'Global Default'}</li>
          <li><strong>Skill Categories:</strong> ${(u.skill_categories || []).join(', ') || 'N/A'}</li>
          <li><strong>Blocked Chat Infractions:</strong> ${userFlags.length} recorded</li>
          <li><strong>Account Standing:</strong> ${u.account_frozen ? `SUSPENDED (${u.suspension_reason})` : 'ACTIVE & CLEAR'}</li>
        </ul>
      </div>
    </div>

    <div class="forensic-box">
      <h4>Associated Bookings History (${userBookings.length})</h4>
      <div style="max-height:180px;overflow-y:auto;">${bookingsRows}</div>
    </div>

    <div class="forensic-box">
      <h4>Immutable Prepaid Credit Ledger History (${userLedger.length})</h4>
      <div style="max-height:180px;overflow-y:auto;">${ledgerRows}</div>
    </div>
  `;

  document.getElementById('profileModal').style.display = 'flex';
}

function closeProfileModal() {
  document.getElementById('profileModal').style.display = 'none';
}

// ============================================================================
// 5. SUPERADMIN, LGU & SCOPED USER PORTAL RENDERERS
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
    alert('UNAUTHORIZED: Only SuperAdmin can toggle platform service verticals.');
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
  if (!currentAuthUser) return;
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
  alert(`Engine 10 Runtime Parameters Committed: Commission Hold = ${comm}%, Skills Board Maturity = ${mat} hours.`);
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
    tbody.innerHTML = contractors.map(c => `
      <tr>
        <td><strong>${c.full_name}</strong> <small style="color:var(--cyan-accent)">@${c.username || 'contractor'}</small></td>
        <td>${c.barangay_zone}, ${c.municipality || 'Angeles City'}</td>
        <td>${(c.skill_categories || []).join(', ')} • <span class="engine-tag">Sub-400cc</span></td>
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
    `).join('');
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
    renderOperatorContractorCommissionTable();
    renderOperatorUsersTable();
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
