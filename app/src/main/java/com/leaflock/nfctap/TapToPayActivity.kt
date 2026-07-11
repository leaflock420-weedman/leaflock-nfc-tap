package com.leaflock.nfctap

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.leaflock.nfctap.databinding.ActivityTapToPayBinding
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Phone SoftPOS: customer taps debit/credit / Apple Pay / Google Pay
 * on THIS phone via Stripe Tap to Pay on Android.
 *
 * Requires:
 * - Android 13+
 * - STRIPE_SECRET_KEY on the backend
 * - Compatible NFC phone (not rooted)
 */
class TapToPayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTapToPayBinding
    private val http = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private var connectedReader: Reader? = null
    private var discoveryCancelable: com.stripe.stripeterminal.external.callable.Cancelable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTapToPayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.productSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ProductCatalog.products
        )

        binding.productSpinner.setOnItemSelectedListener(
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val p = ProductCatalog.products[position]
                    binding.detailText.text = p.detail
                    if (p.priceAud != null && p.code != "custom") {
                        binding.amountInput.setText(
                            if (p.priceAud % 1.0 == 0.0) p.priceAud.toInt().toString()
                            else "%.2f".format(p.priceAud)
                        )
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        )

        binding.connectButton.setOnClickListener { connectTapToPayReader() }
        binding.chargeButton.setOnClickListener { startCharge() }
        binding.newSaleButton.setOnClickListener {
            binding.paidOverlay.visibility = View.GONE
            setStatus("Ready for next sale")
        }
        binding.backStickersButton.setOnClickListener {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }

        setStatus(
            if (BuildConfig.STRIPE_SIMULATED)
                "SIMULATED mode — test without real card.\nConnect reader, then Charge.\nSet STRIPE_SECRET_KEY on server for live."
            else
                "LIVE Tap to Pay.\n1) Connect phone reader\n2) Enter amount / pick kit\n3) Charge — customer taps card on phone"
        )

        // Auto-connect in background
        connectTapToPayReader()
    }

    private fun selectedProduct(): PosProduct =
        binding.productSpinner.selectedItem as PosProduct

    private fun amountCents(): Long? {
        val raw = binding.amountInput.text?.toString()?.trim().orEmpty()
        val product = selectedProduct()
        val dollars = raw.toDoubleOrNull()
            ?: product.priceAud
            ?: return null
        if (dollars <= 0) return null
        return Math.round(dollars * 100.0)
    }

    private fun description(): String {
        val p = selectedProduct()
        val note = binding.noteInput.text?.toString()?.trim().orEmpty()
        return if (note.isNotEmpty()) "${p.label} — $note" else p.label
    }

    private fun connectTapToPayReader() {
        if (!Terminal.isInitialized()) {
            setStatus("Terminal not initialized. Rebuild app / check Application class.")
            return
        }
        setStatus("Connecting Tap to Pay on this phone…")
        binding.connectButton.isEnabled = false

        val config = DiscoveryConfiguration.TapToPayDiscoveryConfiguration(
            isSimulated = BuildConfig.STRIPE_SIMULATED
        )

        discoveryCancelable = Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    if (readers.isEmpty()) return
                    val reader = readers.first()
                    val connConfig = ConnectionConfiguration.TapToPayConnectionConfiguration(
                        locationId = null, // optional STRIPE_LOCATION_ID from backend later
                        autoReconnectOnUnexpectedDisconnect = true,
                        tapToPayReaderListener = null
                    )
                    Terminal.getInstance().connectReader(
                        reader,
                        connConfig,
                        object : ReaderCallback {
                            override fun onSuccess(reader: Reader) {
                                connectedReader = reader
                                runOnUiThread {
                                    binding.connectButton.isEnabled = true
                                    binding.chargeButton.isEnabled = true
                                    setStatus(
                                        "✅ Phone ready as card terminal\n" +
                                            (if (BuildConfig.STRIPE_SIMULATED) "SIMULATED\n" else "LIVE\n") +
                                            "Pick kit → Charge → customer taps card on the back of this phone"
                                    )
                                }
                            }

                            override fun onFailure(e: TerminalException) {
                                runOnUiThread {
                                    binding.connectButton.isEnabled = true
                                    setStatus(
                                        "Connect failed:\n${e.errorMessage}\n\n" +
                                            "Need: Android 13+, NFC on, not rooted, Google Play, " +
                                            "STRIPE_SECRET_KEY on server, internet."
                                    )
                                }
                            }
                        }
                    )
                }
            },
            object : Callback {
                override fun onSuccess() {
                    // discovery finished
                }

                override fun onFailure(e: TerminalException) {
                    runOnUiThread {
                        binding.connectButton.isEnabled = true
                        setStatus("Discover failed:\n${e.errorMessage}")
                    }
                }
            }
        )
    }

    private fun startCharge() {
        val cents = amountCents()
        if (cents == null || cents < 50) {
            toast("Enter amount (min $0.50)")
            return
        }
        if (Terminal.getInstance().connectedReader == null) {
            toast("Connect phone reader first")
            connectTapToPayReader()
            return
        }

        binding.chargeButton.isEnabled = false
        setStatus("Creating payment $${"%.2f".format(cents / 100.0)}…")

        lifecycleScope.launch {
            try {
                val clientSecret = withContext(Dispatchers.IO) {
                    createPaymentIntentSecret(cents, description())
                }
                runOnUiThread {
                    setStatus(
                        "Hold customer card / phone to the BACK of this device…\n" +
                            "$${"%.2f".format(cents / 100.0)}"
                    )
                }
                retrieveAndCollect(clientSecret)
            } catch (e: Exception) {
                runOnUiThread {
                    binding.chargeButton.isEnabled = true
                    setStatus("Error:\n${e.message}")
                    toast(e.message ?: "Payment failed")
                }
            }
        }
    }

    private fun createPaymentIntentSecret(amountCents: Long, description: String): String {
        val json = JSONObject()
            .put("amountCents", amountCents)
            .put("description", description)
            .put("currency", "aud")
            .toString()
        val req = Request.Builder()
            .url("${BuildConfig.POS_API_BASE}/api/stripe/payment_intent")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Server ${resp.code}: $body")
            }
            return JSONObject(body).getString("client_secret")
        }
    }

    private fun retrieveAndCollect(clientSecret: String) {
        Terminal.getInstance().retrievePaymentIntent(
            clientSecret,
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    Terminal.getInstance().collectPaymentMethod(
                        paymentIntent,
                        object : PaymentIntentCallback {
                            override fun onSuccess(paymentIntent: PaymentIntent) {
                                Terminal.getInstance().confirmPaymentIntent(
                                    paymentIntent,
                                    object : PaymentIntentCallback {
                                        override fun onSuccess(paymentIntent: PaymentIntent) {
                                            runOnUiThread {
                                                binding.chargeButton.isEnabled = true
                                                showPaid(
                                                    amount = (paymentIntent.amount ?: 0) / 100.0,
                                                    id = paymentIntent.id ?: ""
                                                )
                                            }
                                        }

                                        override fun onFailure(e: TerminalException) {
                                            runOnUiThread {
                                                binding.chargeButton.isEnabled = true
                                                setStatus("Confirm failed:\n${e.errorMessage}")
                                            }
                                        }
                                    }
                                )
                            }

                            override fun onFailure(e: TerminalException) {
                                runOnUiThread {
                                    binding.chargeButton.isEnabled = true
                                    setStatus("Card read failed:\n${e.errorMessage}\n\nTry again or different card.")
                                }
                            }
                        }
                    )
                }

                override fun onFailure(e: TerminalException) {
                    runOnUiThread {
                        binding.chargeButton.isEnabled = true
                        setStatus("Retrieve PI failed:\n${e.errorMessage}")
                    }
                }
            }
        )
    }

    private fun showPaid(amount: Double, id: String) {
        binding.paidAmount.text = "$" + "%.2f".format(amount) + " AUD"
        binding.paidId.text = id
        binding.paidOverlay.visibility = View.VISIBLE
        setStatus("PAID")
        toast("Payment successful")
    }

    private fun setStatus(msg: String) {
        binding.statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        discoveryCancelable?.cancel(object : Callback {
            override fun onSuccess() {}
            override fun onFailure(e: TerminalException) {}
        })
        super.onDestroy()
    }
}
