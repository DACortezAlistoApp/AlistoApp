package ph.alisto.customer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import ph.alisto.kernel.AlistoKernelClient
import ph.alisto.kernel.BookingRecord

class CustomerMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectedVerticalKey = "skills_marketplace"

    private lateinit var verticalSelectorLayout: LinearLayout
    private lateinit var skillCategorySpinner: Spinner
    private lateinit var dropoffInput: EditText
    private lateinit var bookingsListLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070D19"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 42, 36, 64)
        }

        container.addView(TextView(this).apply {
            text = "ALISTO APP • CUSTOMER"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = "Alisto Network PH • Angeles City & Magalang (Region III)\nArchitect: D. A. Cortez"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 4, 0, 24)
        })

        container.addView(sectionTitle("1. Select Service Vertical (Engine 10 Penetration Flags)"))
        verticalSelectorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 20)
        }
        container.addView(verticalSelectorLayout)

        container.addView(sectionTitle("2. Service Location & Deliverable Details"))

        val municipalitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Angeles City, Pampanga", "Magalang, Pampanga")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Municipality (Region III)", municipalitySpinner))

        val barangaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Brgy. Malabanias", "Brgy. Balibago", "Brgy. Sto. Rosario", "Brgy. Pampang", "Brgy. San Nicolas (Magalang)")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Barangay Zone", barangaySpinner))

        skillCategorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@CustomerMainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Aircon & HVAC", "Electrical Repair", "Plumbing & Water Systems", "Carpentry & Masonry", "Appliance Repair")
            )
            setBackgroundColor(Color.parseColor("#1E293B"))
        }
        container.addView(labeledField("Skill / Trade Category (Phase 1 Marketplace)", skillCategorySpinner))

        val titleInput = styledInput("Service Title (e.g., Split-Type Aircon Cleaning)")
        container.addView(labeledField("Service Request Summary", titleInput))

        val pickupInput = styledInput("House No., Street, Subdivision, Barangay")
        container.addView(labeledField("Service / Pickup Address", pickupInput))

        dropoffInput = styledInput("Drop-off Address (Sub-400cc Non-Expressway Verified)")
        container.addView(labeledField("Drop-off Destination (Delivery / Ride-Hailing Only)", dropoffInput))

        val feeInput = styledInput("1200").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Quoted Service Fee (PHP)", feeInput))

        val tipInput = styledInput("100").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        container.addView(labeledField("Optional Client Tip (PHP - 0% Platform Commission)", tipInput))

        val submitBtn = Button(this).apply {
            text = "DISPATCH BOOKING TO MICRO-KERNEL"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.parseColor("#070D19"))
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val title = titleInput.text.toString().ifBlank { "On-Demand Household Service" }
                val addr = pickupInput.text.toString().ifBlank { "Brgy. Malabanias, Angeles City" }
                val drop = dropoffInput.text.toString()
                val fee = feeInput.text.toString().toDoubleOrNull() ?: 1200.0
                val tip = tipInput.text.toString().toDoubleOrNull() ?: 0.0
                val muni = municipalitySpinner.selectedItem.toString()
                val brgy = barangaySpinner.selectedItem.toString()
                val skill = skillCategorySpinner.selectedItem.toString()

                uiScope.launch {
                    val res = AlistoKernelClient.createBookingRpc(
                        clientId = "44444444-4444-4444-4444-444444444444",
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
                        titleInput.setText("")
                        renderCustomerScreen()
                    }.onFailure { err ->
                        AlertDialog.Builder(this@CustomerMainActivity)
                            .setTitle("Vertical Penetration Guard")
                            .setMessage(err.message)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
        }
        container.addView(submitBtn)

        container.addView(sectionTitle("3. Active Bookings & RLS-Protected In-App Chat"))
        bookingsListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(bookingsListLayout)

        rootScroll.addView(container)
        setContentView(rootScroll)

        uiScope.launch {
            AlistoKernelClient.syncServiceVerticals()
            renderCustomerScreen()
        }
    }

    private fun renderCustomerScreen() {
        verticalSelectorLayout.removeAllViews()
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
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 14) }
                layoutParams = lp

                setOnClickListener {
                    if (!v.isEnabled) {
                        Toast.makeText(
                            this@CustomerMainActivity,
                            "${v.displayName} is currently toggled OFF by SuperAdmin penetration policy.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        selectedVerticalKey = v.key
                        renderCustomerScreen()
                    }
                }
            }

            card.addView(TextView(this).apply {
                text = "${v.displayName}  [${if (v.isEnabled) "ENABLED" else "LOCKED BY SUPERADMIN"}]"
                setTextColor(if (v.isEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#EF4444"))
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

        val isSkills = selectedVerticalKey == "skills_marketplace"
        skillCategorySpinner.visibility = if (isSkills) View.VISIBLE else View.GONE
        dropoffInput.visibility = if (isSkills) View.GONE else View.VISIBLE

        bookingsListLayout.removeAllViews()
        AlistoKernelClient.bookings.forEach { b ->
            bookingsListLayout.addView(buildBookingCard(b))
        }
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
            setTextColor(Color.parseColor("#FFB300"))
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
                "Anti-Circumvention Moderation Trigger Active.\nOff-platform phrases ('pay me cash direct', 'cancel the booking', 'text me outside') are automatically blocked and logged."
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
                        val res = AlistoKernelClient.sendModeratedChatMessageRpc(
                            bookingId = booking.id,
                            senderLabel = "Customer",
                            messageBody = body
                        )
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

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#FFB300"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 8)
        }
    }

    private fun styledInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(20, 16, 20, 16)
        }
    }

    private fun labeledField(label: String, field: View): View {
        return LinearLayout(this).apply {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}
