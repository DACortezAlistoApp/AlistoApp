package ph.alisto.customer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import ph.alisto.kernel.AlistoBrandHeaderView
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.BookingRecord

class CustomerMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedVerticalKey = "skills_marketplace"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AlistoKernelClient.currentSessionUser == null) {
            showLoginGate()
        } else {
            showAuthenticatedCustomerPortal()
        }
    }

    private fun showLoginGate() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070A12")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 64, 42, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO APP • CUSTOMER",
                "Engine 01 Database Authentication Gate"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 32) }
            }
        )

        val idInput = styledInput("Username, Mobile Phone (+63...), or Email")
        container.addView(labeledField("Customer Login Identifier (public.users)", idInput))

        val pwInput = styledInput("Password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(labeledField("Account Password", pwInput))

        val loginBtn = Button(this).apply {
            text = "SIGN IN TO ALISTO CUSTOMER APP"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070A12"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val idStr = idInput.text.toString()
                val pwStr = pwInput.text.toString()
                isEnabled = false
                text = "AUTHENTICATING AGAINST SUPABASE..."
                uiScope.launch {
                    val res = AlistoKernelClient.authenticateUserRpc(idStr, pwStr, "customer")
                    isEnabled = true
                    text = "SIGN IN TO ALISTO CUSTOMER APP"
                    res.onSuccess {
                        showAuthenticatedCustomerPortal()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Authentication Denied")
                            .setMessage(err.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
        container.addView(loginBtn)

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun showAuthenticatedCustomerPortal() {
        val user = AlistoKernelClient.currentSessionUser ?: return showLoginGate()

        val rootScroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#070D19")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(
            AlistoBrandHeaderView(
                this,
                "ALISTO APP • CUSTOMER",
                "${user.fullName} (@${user.username})"
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }
        )

        val sessionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
            addView(Button(this@CustomerMainActivity).apply {
                text = "Refresh Live Kernel"
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    uiScope.launch {
                        AlistoKernelClient.syncServiceVerticals()
                        showAuthenticatedCustomerPortal()
                    }
                }
            })
            addView(Button(this@CustomerMainActivity).apply {
                text = "Sign Out"
                setBackgroundColor(Color.parseColor("#7F1D1D"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    AlistoKernelClient.signOut()
                    showLoginGate()
                }
            })
        }
        container.addView(sessionBar)

        container.addView(sectionTitle("1. Select Service Vertical (Engine 10 Penetration Flags)"))
        val verticalSelectorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 20)
        }
        AlistoKernelClient.verticals.forEach { v ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
                val bg = when {
                    !v.isEnabled -> "#111827"
                    selectedVerticalKey == v.key -> "#164E63"
                    else -> "#1E293B"
                }
                setBackgroundColor(Color.parseColor(bg))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }

                setOnClickListener {
                    if (!v.isEnabled) {
                        Toast.makeText(
                            this@CustomerMainActivity,
                            "${v.displayName} is currently toggled OFF by SuperAdmin penetration policy.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        selectedVerticalKey = v.key
                        showAuthenticatedCustomerPortal()
                    }
                }
            }
            card.addView(TextView(this).apply {
                text = "${v.displayName}  [${if (v.isEnabled) "ENABLED" else "LOCKED BY SUPERADMIN"}]"
                setTextColor(if (v.isEnabled) Color.parseColor("#00F2FE") else Color.parseColor("#EF4444"))
                setTypeface(null, Typeface.BOLD)
                textSize = 14f
            })
            card.addView(TextView(this).apply {
                text = v.description
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
            })
            verticalSelectorLayout.addView(card)
        }
        container.addView(verticalSelectorLayout)

        container.addView(sectionTitle("2. Service Location & Deliverable Details"))

        val municipalitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Angeles City", "Magalang")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Municipality (Region III)", municipalitySpinner))

        val barangaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Malabanias", "Balibago", "Sto. Rosario", "Pampang", "Cutcut", "San Nicolas (Magalang)")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Barangay Zone", barangaySpinner))

        val skillCategorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Aircon & HVAC", "Electrical Repair", "Plumbing & Water Systems", "Carpentry & Masonry", "Appliance Repair")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        if (selectedVerticalKey == "skills_marketplace") {
            container.addView(labeledField("Skill / Trade Category", skillCategorySpinner))
        }

        val titleInput = styledInput("Service Title (e.g., Split-Type Aircon Cleaning)")
        container.addView(labeledField("Service Request Summary", titleInput))

        val pickupInput = styledInput("House No., Street, Subdivision, Barangay")
        container.addView(labeledField("Service / Pickup Address", pickupInput))

        val dropoffInput = styledInput("Drop-off Address (Sub-400cc Non-Expressway Verified)")
        if (selectedVerticalKey != "skills_marketplace") {
            container.addView(labeledField("Drop-off Destination", dropoffInput))
        }

        val feeInput = styledInput("1200").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Quoted Service Fee (PHP)", feeInput))

        val tipInput = styledInput("100").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Optional Client Tip (PHP - 0% Platform Commission)", tipInput))

        val submitBtn = Button(this).apply {
            text = "DISPATCH BOOKING TO MICRO-KERNEL"
            setBackgroundColor(Color.parseColor("#00F2FE"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val title = titleInput.text.toString().ifBlank { "On-Demand Household Service" }
                val addr = pickupInput.text.toString().ifBlank { "${user.barangayZone}, ${user.municipality}" }
                val drop = dropoffInput.text.toString()
                val fee = feeInput.text.toString().toDoubleOrNull() ?: 1200.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0
                val muni = municipalitySpinner.selectedItem.toString()
                val brgy = barangaySpinner.selectedItem.toString()
                val skill = skillCategorySpinner.selectedItem.toString()

                uiScope.launch {
                    val res = AlistoKernelClient.createBookingRpc(
                        verticalKey = selectedVerticalKey,
                        municipality = muni,
                        barangayZone = brgy,
                        skillCategory = if (selectedVerticalKey == "skills_marketplace") skill else "Sub-400cc Non-Expressway Transit",
                        serviceTitle = title,
                        pickupAddress = addr,
                        dropoffAddress = drop,
                        quotedFee = fee,
                        tipAmount = tip
                    )
                    res.onSuccess {
                        Toast.makeText(this@CustomerMainActivity, "Booking ${it.bookingRef} Dispatched!", Toast.LENGTH_SHORT).show()
                        showAuthenticatedCustomerPortal()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Booking Guard")
                            .setMessage(err.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
        container.addView(submitBtn)

        container.addView(sectionTitle("3. My Bookings & RLS-Protected In-App Chat"))
        val myBookings = AlistoKernelClient.bookings.filter {
            user.role == "super_admin" || it.clientId == user.id
        }
        myBookings.forEach { b ->
            container.addView(buildBookingCard(b))
        }

        rootScroll.addView(container)
        setContentView(rootScroll)
    }

    private fun buildBookingCard(b: BookingRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.parseColor("#1E293B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
        }

        card.addView(TextView(this).apply {
            text = "${b.bookingRef} • ${b.status}"
            setTextColor(Color.parseColor("#FBBF24"))
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = "${b.serviceTitle}\nZone: ${b.barangayZone}, ${b.municipality}\nFee: PHP ${b.quotedFee} (+PHP ${b.tipAmount} Tip)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 6, 0, 10)
        })

        val chatBtn = Button(this).apply {
            text = "OPEN SECURE IN-APP CHAT"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener { openChatDialog(b) }
        }
        card.addView(chatBtn)
        return card
    }

    private fun openChatDialog(booking: BookingRecord) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 16)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val transcriptView = TextView(this).apply {
            val msgs = AlistoKernelClient.chatTranscripts[booking.id] ?: emptyList()
            text = if (msgs.isEmpty()) {
                "Anti-Circumvention Moderation Trigger Active.\nOff-platform phrases ('pay me cash direct', 'cancel the booking') are automatically blocked and logged."
            } else {
                msgs.joinToString("\n\n") { "${it.senderName}: ${it.body}" }
            }
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setPadding(0, 0, 0, 20)
        }
        dialogLayout.addView(transcriptView)

        val msgInput = styledInput("Type message to contractor...")
        dialogLayout.addView(msgInput)

        AlertDialog.Builder(this)
            .setTitle("Booking Chat • ${booking.bookingRef}")
            .setView(dialogLayout)
            .setPositiveButton("Send") { _, _ ->
                val body = msgInput.text.toString()
                if (body.isNotBlank()) {
                    uiScope.launch {
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(booking.id, body)
                        res.onSuccess {
                            Toast.makeText(this@CustomerMainActivity, "Message sent.", Toast.LENGTH_SHORT).show()
                        }.onFailure { err ->
                            AlertDialog.Builder(this@CustomerMainActivity)
                                .setTitle("Moderation / RLS Policy Block")
                                .setMessage(err.message)
                                .setPositiveButton("Understood", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        setTextColor(Color.parseColor("#FBBF24"))
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 20, 0, 8)
    }

    private fun styledInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setHintTextColor(Color.parseColor("#64748B"))
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#1E293B"))
        setPadding(20, 16, 20, 16)
    }

    private fun labeledField(label: String, field: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, 16)
        addView(TextView(this@CustomerMainActivity).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 0, 0, 4)
        })
        addView(field)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
