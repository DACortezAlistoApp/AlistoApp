// ============================================================================
// ALISTO NETWORK PH - SUPERADMIN & LGU/PESO GOVERNANCE CONSOLE (LIVE KERNEL)
// Author & Chief Systems Architect: D. A. Cortez
// ============================================================================

const _K_U = atob("aHR0cHM6Ly9mbWpqeWhtdGRzdmh1ZmtneWpwci5zdXBhYmFzZS5jbw==");
const _K_A = atob("ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnBjM01pT2lKemRYQmhZbUZ6WlNJc0luSmxaaUk2SW1adGFtcDVhRzEwWkhOMmFIVm1hMmQ1YW5CeUlpd2ljbTlzWlNJNkltRnViMjRpTENKcFlYUWlPakUzT1RRNU1URTVPRElzSW1WNGNDSTZNakV4TURRNE56azRNbjAueGV2a21NQkFuSVVjTktrcDM0UFRubWxrLTM4NVNVd3B3aGJWMEtheWd6cw==");
let supabaseClient = null;
const SUPERADMIN_UUID = '11111111-1111-1111-1111-111111111111';

const consoleState = {
  verticals: [],
  moderationRules: [],
  moderationAuditLogs: [],
  contractors: [],
  bookings: []
};

function switchConsoleView(viewKey) {
  document.querySelectorAll('.role-tab').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.view === viewKey);
  });
  document.querySelectorAll('.role-view').forEach(sec => {
    sec.classList.toggle('active', sec.id === 'view-' + viewKey);
  });
  renderAll();
}

async function syncFromSupabase() {
  if (!supabaseClient) return;
  try {
    const { data: vData } = await supabaseClient.from('service_verticals').select('*').order('vertical_key');
    if (vData) consoleState.verticals = vData;

    const { data: cfgData } = await supabaseClient.from('system_config').select('*');
    if (cfgData) {
      const commRow = cfgData.find(c => c.config_key === 'platform_commission_rate');
      const matRow = cfgData.find(c => c.config_key === 'skills_maturity_window_hours');
      if (commRow && document.getElementById('cfgCommission')) {
        document.getElementById('cfgCommission').value = Math.round( parseFloat(commRow.config_value) * 100 );
      }
      if (matRow && document.getElementById('cfgMaturity')) {
        document.getElementById('cfgMaturity').value = parseInt(matRow.config_value, 10);
      }
    }

    const { data: rData } = await supabaseClient.from('moderation_rules').select('*').order('created_at');
    if (rData) consoleState.moderationRules = rData;

    const { data: aData } = await supabaseClient.from('moderation_audit_logs').select('*').order('logged_at', { ascending: false });
    if (aData) consoleState.moderationAuditLogs = aData;

    const { data: bData } = await supabaseClient.from('bookings').select('*').order('created_at', { ascending: false });
    if (bData) consoleState.bookings = bData;

    const { data: uData } = await supabaseClient.from('users').select('*').eq('role', 'contractor');
    if (uData) consoleState.contractors = uData;
  } catch (err) {
    console.error('Kernel sync error:', err);
  }
  renderAll();
}

function subscribeRealtimeKernel() {
  if (!supabaseClient) return;
  supabaseClient
    .channel('alisto-superadmin-live')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'service_verticals' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'bookings' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'moderation_rules' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'moderation_audit_logs' }, () => syncFromSupabase())
    .on('postgres_changes', { event: '*', schema: 'public', table: 'users' }, () => syncFromSupabase())
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

function renderSuperAdmin() {
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
          <td>${log.logged_at ? new Date(log.logged_at).toLocaleTimeString() : 'Recent'}</td>
          <td><code>${log.booking_id ? log.booking_id.slice(0, 8) : 'BOOKING'}</code></td>
          <td><code>${log.sender_id ? log.sender_id.slice(0, 8) : 'USER'}</code></td>
          <td style="color:#fca5a5">"${log.flagged_message}"</td>
          <td><code>${log.violation_type}: "${log.matched_pattern}"</code></td>
        </tr>
      `).join('');
    }
  }
}

async function toggleVertical(verticalKey) {
  const target = consoleState.verticals.find(v => v.vertical_key === verticalKey);
  if (!target) return;
  const nextState = !target.is_enabled;
  target.is_enabled = nextState;
  renderAll();

  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('admin_toggle_service_vertical', {
      p_admin_id: SUPERADMIN_UUID,
      p_vertical_key: verticalKey,
      p_is_enabled: nextState
    });
    if (error) alert('RPC Error: ' + error.message);
    await syncFromSupabase();
  }
}

async function saveRuntimeConfig() {
  const comm = parseFloat(document.getElementById('cfgCommission').value) || 12;
  const mat = parseInt(document.getElementById('cfgMaturity').value, 10) || 72;
  const rateDecimal = (comm / 100.0).toFixed(2);

  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('admin_update_system_config_rpc', {
      p_commission_rate: String(rateDecimal),
      p_maturity_hours: String(mat)
    });
    if (error) {
      alert('Run 02_alisto_admin_rpcs.sql in Supabase SQL Editor to persist config: ' + error.message);
      return;
    }
    await syncFromSupabase();
  }
  alert(`Engine 10 Runtime Parameters Committed to PostgreSQL: Commission Hold = ${comm}%, Skills Board Maturity = ${mat} hours.`);
}

async function addModerationRule() {
  const ruleType = document.getElementById('newRuleType').value;
  const pattern = document.getElementById('newRulePattern').value.trim();
  if (!pattern) return;

  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('admin_manage_moderation_rule_rpc', {
      p_operation: 'ADD',
      p_rule_type: ruleType,
      p_pattern: pattern.toLowerCase(),
      p_action_type: 'block'
    });
    if (error) {
      alert('RPC Error: ' + error.message);
      return;
    }
    document.getElementById('newRulePattern').value = '';
    await syncFromSupabase();
  }
}

async function removeModerationRule(id) {
  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('admin_manage_moderation_rule_rpc', {
      p_operation: 'DELETE',
      p_rule_id: id
    });
    if (error) {
      alert('RPC Error: ' + error.message);
      return;
    }
    await syncFromSupabase();
  }
}

function renderLguAdmin() {
  const tbody = document.getElementById('lguContractorTable');
  if (tbody) {
    tbody.innerHTML = consoleState.contractors.map(c => `
      <tr>
        <td><strong>${c.full_name}</strong></td>
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

  const bBody = document.getElementById('liveBookingsTable');
  if (bBody) {
    bBody.innerHTML = consoleState.bookings.map(b => `
      <tr>
        <td><code>${b.booking_ref}</code></td>
        <td><code>${b.vertical_key}</code></td>
        <td>${b.barangay_zone}, ${b.municipality || 'Angeles City'}</td>
        <td>PHP ${Number(b.quoted_fee).toFixed(2)} (+${Number(b.tip_amount || 0).toFixed(2)} tip)</td>
        <td>PHP ${Number(b.commission_locked || 0).toFixed(2)}</td>
        <td><span class="engine-tag">${b.status}</span></td>
        <td>
          ${Number(b.commission_locked || 0) > 0 && b.status !== 'COMPLETED' && b.status !== 'CANCELLED'
            ? `<button class="btn-danger" onclick="reverseBookingDispute('${b.id}', '${b.booking_ref}')">Reverse 12% Hold</button>`
            : `<span style="color:var(--text-muted);font-size:0.75rem;">No Active Hold</span>`}
        </td>
      </tr>
    `).join('');
  }
}

async function toggleContractorEndorsement(cId, nextEndorsed) {
  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('lgu_toggle_contractor_endorsement_rpc', {
      p_contractor_id: cId,
      p_lgu_endorsed: nextEndorsed
    });
    if (error) alert('RPC Error: ' + error.message);
    await syncFromSupabase();
  }
}

async function reverseBookingDispute(bookingId, bookingRef) {
  const reason = prompt(`Engine 09 Dispute Vault — Enter audit reason for reversing the 12% commission hold on ${bookingRef}:`, 'Client/Contractor verified cancellation');
  if (!reason) return;
  if (supabaseClient) {
    const { error } = await supabaseClient.rpc('admin_resolve_dispute_reversal_rpc', {
      p_booking_id: bookingId,
      p_admin_id: SUPERADMIN_UUID,
      p_resolution_notes: reason
    });
    if (error) alert('Dispute RPC Error: ' + error.message);
    await syncFromSupabase();
  }
}

function renderAll() {
  renderVerticalStrip();
  renderSuperAdmin();
  renderLguAdmin();
}

window.addEventListener('DOMContentLoaded', async () => {
  if (window.supabase) {
    supabaseClient = window.supabase.createClient(_K_U, _K_A);
    const badge = document.getElementById('kernelConnectionBadge');
    if (badge) badge.textContent = 'KERNEL: SUPABASE LIVE (fmjjyhmtdsvhufkgyjpr)';
    await syncFromSupabase();
    subscribeRealtimeKernel();
  } else {
    renderAll();
  }
});
