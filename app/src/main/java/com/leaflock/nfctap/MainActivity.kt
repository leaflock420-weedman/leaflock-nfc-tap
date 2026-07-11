package com.leaflock.nfctap

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.leaflock.nfctap.databinding.ActivityMainBinding

/**
 * LeafLock staff NFC helper.
 *
 * IMPORTANT:
 * - Debit/credit contactless cards are NOT readable product tags.
 *   They use EMV (IsoDep). You cannot pull PayPal money from a card this way.
 * - Program blank NTAG stickers with POS/PayPal.me URLs for product taps.
 * - Optional "any tap" mode: any NFC field (including a card) only *triggers*
 *   opening your PayPal.me / POS link for the amount you typed — it does not
 *   charge the card.
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var writeMode = false
    private var listenMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        updateNfcStatusBanner()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ProductCatalog.products
        )
        binding.productSpinner.adapter = adapter
        binding.productSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val p = ProductCatalog.products[position]
                    binding.productDetail.text = p.detail
                    if (p.priceAud != null && p.code != "custom") {
                        binding.amountInput.setText(
                            if (p.priceAud % 1.0 == 0.0) p.priceAud.toInt().toString()
                            else "%.2f".format(p.priceAud)
                        )
                    }
                    refreshPreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        binding.modeGroup.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        binding.amountInput.setOnFocusChangeListener { _, _ -> refreshPreview() }
        binding.amountInput.addTextChangedListener(SimpleTextWatcher { refreshPreview() })
        binding.anyTapSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                setStatus(
                    "ANY TAP mode ON\n\n" +
                        "When you tap a debit card or blank tag, the app will open your payment link " +
                        "for the amount/product selected.\n\n" +
                        "This does NOT charge the debit card. It only opens PayPal/POS on your phone."
                )
            } else {
                updateNfcStatusBanner()
            }
        }

        binding.openLinkButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            openUrl(url)
            setStatus("Opened:\n$url")
        }

        binding.writeTagButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            if (!ensureNfcOn()) return@setOnClickListener
            writeMode = true
            listenMode = false
            enableReaderMode()
            setStatus(
                "✍️ WRITE MODE — use a blank NTAG sticker (NOT a debit card)\n\n" +
                    "Hold sticker on the back of the phone.\n\n$url"
            )
            toast("Use a blank NFC sticker — not a bank card")
            binding.listenButton.text = "Start NFC listen (read tags)"
        }

        binding.listenButton.setOnClickListener {
            if (!ensureNfcOn()) return@setOnClickListener
            writeMode = false
            listenMode = !listenMode
            if (listenMode) {
                enableReaderMode()
                binding.listenButton.text = "Stop NFC listen"
                setStatus(
                    "👂 LISTENING\n\n" +
                        "• Programmed sticker → opens its URL\n" +
                        "• Debit/credit card → cannot be “read” as a product tag\n" +
                        (if (binding.anyTapSwitch.isChecked)
                            "• ANY TAP is ON → card/tag will open your payment link"
                        else
                            "• Turn on “Any NFC tap opens payment link” below if you want card-as-button")
                )
            } else {
                disableReaderMode()
                binding.listenButton.text = "Start NFC listen (read tags)"
                setStatus("Stopped listening")
            }
        }

        refreshPreview()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onTagDiscovered(tag: Tag) {
        mainHandler.post {
            try {
                val techs = tag.techList.joinToString()
                val looksLikeBankCard =
                    techs.contains("IsoDep", ignoreCase = true) &&
                        Ndef.get(tag) == null &&
                        NdefFormatable.get(tag) == null

                if (writeMode) {
                    if (looksLikeBankCard) {
                        writeMode = false
                        if (!listenMode) disableReaderMode()
                        setStatus(
                            "❌ That is a debit/credit card\n\n" +
                                "Bank cards cannot be programmed as product tags.\n\n" +
                                "Buy blank NTAG213 NFC stickers and write those instead."
                        )
                        toast("Cannot write a bank card")
                        return@post
                    }
                    val url = buildPaymentUrl()
                    if (url == null) {
                        setStatus("Write cancelled — pick product / amount first")
                        writeMode = false
                        return@post
                    }
                    val err = writeUrlToTag(tag, url)
                    writeMode = false
                    if (!listenMode) disableReaderMode()
                    if (err == null) {
                        setStatus("✅ TAG WRITTEN\n\n$url\n\nCustomer taps sticker with their phone → payment page opens.")
                        toast("Tag written OK")
                    } else {
                        setStatus("❌ WRITE FAILED\n\n$err")
                        toast("Write failed")
                    }
                    return@post
                }

                if (listenMode) {
                    // 1) Prefer real NDEF product / URL tags
                    val url = readUrlFromTag(tag)
                    if (url != null) {
                        setStatus("Tag read — opening\n$url")
                        openUrl(url)
                        return@post
                    }
                    val code = readTextCodeFromTag(tag)
                    if (code != null) {
                        val base = getString(R.string.pos_base_url).trimEnd('/')
                        val built = "$base/?product=${code.trim()}"
                        setStatus("Product code on tag: $code\nOpening $built")
                        openUrl(built)
                        return@post
                    }

                    // 2) Bank card or empty tag
                    if (looksLikeBankCard) {
                        if (binding.anyTapSwitch.isChecked) {
                            val payUrl = buildPaymentUrl()
                            if (payUrl != null) {
                                setStatus(
                                    "Bank card detected (not readable as data).\n" +
                                        "ANY TAP → opening your payment link:\n$payUrl\n\n" +
                                        "Note: the card was NOT charged by NFC. Customer still pays in PayPal/POS."
                                )
                                openUrl(payUrl)
                            } else {
                                setStatus("Bank card tapped — enter amount / product first for ANY TAP mode")
                            }
                        } else {
                            setStatus(
                                "❌ Debit/credit card — cannot read product data\n\n" +
                                    "Contactless cards are locked payment chips (EMV).\n\n" +
                                    "What works:\n" +
                                    "1) Blank NFC stickers with your POS link written on them\n" +
                                    "2) Or turn ON “Any NFC tap opens payment link” so a card only acts as a button to open PayPal.me / POS (still not a card charge)"
                            )
                            toast("Bank card is not a product tag")
                        }
                        return@post
                    }

                    // Empty / unknown non-card tag
                    if (binding.anyTapSwitch.isChecked) {
                        val payUrl = buildPaymentUrl()
                        if (payUrl != null) {
                            setStatus("Empty tag — ANY TAP opening:\n$payUrl")
                            openUrl(payUrl)
                        } else {
                            setStatus("Empty tag — pick product or amount first")
                        }
                    } else {
                        setStatus(
                            "No URL/text on this tag.\n" +
                                "Use Write to program an NTAG sticker, or enable ANY TAP."
                        )
                        toast("Empty / unknown tag")
                    }
                }
            } catch (e: Exception) {
                setStatus("NFC error: ${e.message}")
                toast("NFC error: ${e.message}")
            }
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val data = intent.dataString
            if (!data.isNullOrBlank()) {
                setStatus("Opened from tag:\n$data")
                openUrl(data)
            }
        }
    }

    private fun selectedProduct(): PosProduct =
        binding.productSpinner.selectedItem as PosProduct

    private fun buildPaymentUrl(): String? {
        val base = getString(R.string.pos_base_url).trimEnd('/')
        val product = selectedProduct()
        val amountRaw = binding.amountInput.text?.toString()?.trim().orEmpty()

        if (binding.modePayPalMe.isChecked) {
            val user = getString(R.string.paypal_me_user).trim()
            if (user.isEmpty() || user.equals("YOUR_PAYPAL_ME_USERNAME", true)) {
                toast("PayPal.me username not set")
                return null
            }
            val amount = amountRaw.ifEmpty {
                product.priceAud?.let { "%.2f".format(it) } ?: ""
            }
            if (amount.isEmpty()) {
                toast("Enter an amount for PayPal.me")
                return null
            }
            val normalized = amount.toDoubleOrNull()?.let { "%.2f".format(it) } ?: amount
            return "https://paypal.me/$user/$normalized"
        }

        return when {
            product.code == "custom" -> {
                val amount = amountRaw.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    toast("Enter a valid custom amount")
                    return null
                }
                "$base/?amount=${"%.2f".format(amount)}"
            }
            product.priceAud != null -> "$base/?product=${product.code}"
            else -> {
                val amount = amountRaw.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    toast("Pick a product or enter custom amount")
                    return null
                }
                "$base/?amount=${"%.2f".format(amount)}"
            }
        }
    }

    private fun refreshPreview() {
        try {
            val base = getString(R.string.pos_base_url).trimEnd('/')
            val product = selectedProduct()
            val amountRaw = binding.amountInput.text?.toString()?.trim().orEmpty()
            val preview = if (binding.modePayPalMe.isChecked) {
                val user = getString(R.string.paypal_me_user)
                val amount = amountRaw.ifEmpty {
                    product.priceAud?.let { "%.2f".format(it) } ?: "?"
                }
                "https://paypal.me/$user/$amount"
            } else if (product.code == "custom") {
                val amount = amountRaw.toDoubleOrNull()
                if (amount != null && amount > 0) "$base/?amount=${"%.2f".format(amount)}"
                else "$base/?amount=?"
            } else {
                "$base/?product=${product.code}"
            }
            binding.linkPreview.text = preview
        } catch (_: Exception) {
            binding.linkPreview.text = "…"
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No browser: ${e.message}")
        }
    }

    private fun ensureNfcOn(): Boolean {
        val adapter = nfcAdapter
        if (adapter == null) {
            toast("This phone has no NFC")
            setStatus("No NFC hardware on this phone")
            return false
        }
        if (!adapter.isEnabled) {
            toast("Turn ON NFC in Settings")
            setStatus("NFC is OFF — Settings → Connected devices → NFC → ON")
            return false
        }
        return true
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            toast("Turn NFC ON in Settings")
            return
        }
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE
        try {
            adapter.enableReaderMode(this, this, flags, null)
        } catch (e: Exception) {
            toast("Could not start NFC: ${e.message}")
            setStatus("NFC start failed: ${e.message}")
        }
    }

    private fun disableReaderMode() {
        try {
            nfcAdapter?.disableReaderMode(this)
        } catch (_: Exception) {
        }
    }

    private fun writeUrlToTag(tag: Tag, url: String): String? {
        // Refuse bank cards early
        if (IsoDep.get(tag) != null && Ndef.get(tag) == null && NdefFormatable.get(tag) == null) {
            return "This is a bank card, not a programmable sticker"
        }
        return try {
            val uriRecord = NdefRecord.createUri(url)
            val product = selectedProduct()
            val textRecord = NdefRecord.createTextRecord("en", product.code)
            val message = NdefMessage(arrayOf(uriRecord, textRecord))
            val bytes = message.toByteArray()

            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "Tag is locked (not writable)"
                    if (ndef.maxSize < bytes.size) {
                        return "Tag too small (${ndef.maxSize} bytes, need ${bytes.size})"
                    }
                    ndef.writeNdefMessage(message)
                } finally {
                    try {
                        ndef.close()
                    } catch (_: Exception) {
                    }
                }
                null
            } else {
                val formatable = NdefFormatable.get(tag)
                    ?: return "Not a programmable NTAG sticker (got: ${tag.techList.joinToString()})"
                formatable.connect()
                try {
                    formatable.format(message)
                } finally {
                    try {
                        formatable.close()
                    } catch (_: Exception) {
                    }
                }
                null
            }
        } catch (e: Exception) {
            e.message ?: "Unknown write error"
        }
    }

    private fun readUrlFromTag(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val msg = try {
                ndef.ndefMessage ?: ndef.cachedNdefMessage
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            } ?: return null

            for (record in msg.records) {
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_URI)
                ) {
                    return parseUriPayload(record.payload)
                }
                if (record.tnf == NdefRecord.TNF_ABSOLUTE_URI) {
                    return String(record.payload, Charsets.UTF_8)
                }
            }
            for (record in msg.records) {
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_TEXT)
                ) {
                    val text = parseTextPayload(record.payload)
                    if (text.startsWith("http://") || text.startsWith("https://")) return text
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readTextCodeFromTag(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val msg = try {
                ndef.ndefMessage ?: ndef.cachedNdefMessage
            } finally {
                try {
                    ndef.close()
                } catch (_: Exception) {
                }
            } ?: return null
            for (record in msg.records) {
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(NdefRecord.RTD_TEXT)
                ) {
                    val text = parseTextPayload(record.payload).trim()
                    if (text.isNotEmpty() && !text.startsWith("http")) return text
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTextPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt()
        val langLen = status and 0x3F
        val isUtf16 = (status and 0x80) != 0
        val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
        val start = 1 + langLen
        if (start >= payload.size) return ""
        return String(payload, start, payload.size - start, charset)
    }

    private fun parseUriPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val code = payload[0].toInt() and 0xFF
        val prefixes = arrayOf(
            "", "http://www.", "https://www.", "http://", "https://",
            "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
            "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
            "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
            "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
            "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
            "urn:epc:raw:", "urn:epc:", "urn:nfc:"
        )
        val prefix = if (code < prefixes.size) prefixes[code] else ""
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    private fun updateNfcStatusBanner() {
        val adapter = nfcAdapter
        when {
            adapter == null -> setStatus("This phone has no NFC hardware.")
            !adapter.isEnabled -> setStatus("NFC is OFF.\nSettings → Connected devices → NFC → ON")
            else -> setStatus(
                "NFC ready.\n\n" +
                    "• Product tags = blank NTAG stickers with a URL written on them\n" +
                    "• Debit cards CANNOT be read as product tags (bank security)\n" +
                    "• Optional: “Any NFC tap” uses a card only as a button to open PayPal/POS"
            )
        }
    }

    private fun setStatus(msg: String) {
        binding.statusText.text = msg
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (writeMode || listenMode) enableReaderMode()
        refreshPreview()
    }

    override fun onPause() {
        super.onPause()
        disableReaderMode()
    }
}

private class SimpleTextWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChange()
}
