package ph.alisto.kernel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object AlistoComplianceGate {
    private var otaPromptShownForCode: Int = -1
    private var consentDialogVisible: Boolean = false

    fun checkOtaUpdate(
        activity: Activity,
        portalRole: String,
        currentVersionCode: Int,
        currentVersionName: String
    ) {
        val rel = AlistoKernelClient.currentReleaseManifest ?: return
        if (rel.latestVersionCode <= currentVersionCode) return
        if (otaPromptShownForCode == rel.latestVersionCode && currentVersionCode >= rel.minSupportedVersionCode) return

        otaPromptShownForCode = rel.latestVersionCode
        val mandatory = currentVersionCode < rel.minSupportedVersionCode
        val apkUrl = if (portalRole == "contractor") rel.contractorApkUrl else rel.customerApkUrl

        val builder = AlertDialog.Builder(activity)
            .setTitle(if (mandatory) "Required Alisto Security Update" else "Alisto Update Available (v${rel.latestVersionName})")
            .setMessage(
                "Installed Build: v$currentVersionName (code $currentVersionCode)\n" +
                "Latest Release: v${rel.latestVersionName} (code ${rel.latestVersionCode})\n\n" +
                rel.releaseNotes
            )
            .setCancelable(!mandatory)
            .setPositiveButton("Download APK Update") { _, _ ->
                if (apkUrl.isNotBlank()) {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)))
                }
            }

        if (!mandatory) {
            builder.setNegativeButton("Later", null)
        }
        builder.show()
    }

    fun enforceRa10173ConsentIfRequired(
        activity: Activity,
        scope: CoroutineScope,
        portalRole: String,
        onAccepted: () -> Unit = {}
    ) {
        val state = AlistoKernelClient.currentConsentState ?: return
        if (!state.requiresConsent || consentDialogVisible) return
        consentDialogVisible = true

        val scroll = ScrollView(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 28, 40, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val roleClause = if (portalRole == "contractor") {
            "2. Independent Contractor & Telemetry Notice: You operate as an independent service provider. Live 10-second GPS telemetry activates strictly during ON_THE_WAY and ONGOING states and stops upon COMPLETED."
        } else {
            "2. Customer Dispatch & Safety Notice: Address pins, waypoint stops, and active coordination messages are shared only with your assigned verified contractor and Alisto audit logs."
        }

        container.addView(TextView(activity).apply {
            text = "Data Privacy Act of 2012 (Republic Act No. 10173) Consent & Terms\nPolicy Version: ${state.requiredVersion}\n\n" +
                "1. Personal Data Processing: Alisto Network PH collects and processes your identity, mobile number, location pins, KYC verification files, and booking audit records solely to deliver marketplace dispatch, safety monitoring, and dispute resolution under RA 10173.\n\n" +
                "$roleClause\n\n" +
                "3. Platform Moderation & Rights: In-app Engine 11 chat is monitored against off-platform solicitation. Under RA 10173, you retain the right to access, correct, or request deletion of eligible personal records."
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 0, 0, 16)
        })

        val cbAgree = CheckBox(activity).apply {
            text = "I consent under RA 10173 and accept the Alisto Network PH Terms of Service."
            setTextColor(Color.parseColor("#00F2FE"))
        }
        container.addView(cbAgree)
        scroll.addView(container)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("RA 10173 Data Privacy & Terms")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("Accept & Continue", null)
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                if (!cbAgree.isChecked) {
                    Toast.makeText(activity, "Please check the RA 10173 consent box to proceed.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                btn.isEnabled = false
                scope.launch {
                    val res = AlistoKernelClient.recordPrivacyConsentRpc(
                        consentVersion = state.requiredVersion,
                        deviceLabel = "android_${portalRole}_api36"
                    )
                    res.onSuccess {
                        consentDialogVisible = false
                        dialog.dismiss()
                        Toast.makeText(activity, "RA 10173 consent recorded (${state.requiredVersion}).", Toast.LENGTH_SHORT).show()
                        onAccepted()
                    }.onFailure { err ->
                        btn.isEnabled = true
                        Toast.makeText(activity, "Consent error: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }
}
