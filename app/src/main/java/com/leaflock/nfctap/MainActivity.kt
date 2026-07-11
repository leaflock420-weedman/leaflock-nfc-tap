package com.leaflock.nfctap

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
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
 * LeafLock staff NFC helper — Reader Mode (reliable write/read).
 * Tags store full HTTPS POS links so customer phones open Chrome without any app.
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

        binding.openLinkButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            openUrl(url)
            setStatus("Opened:\n$url")
        }

        binding.writeTagButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            if (nfcAdapter == null) {
                toast("This phone has no NFC")
                return@setOnClickListener
            }
            if (nfcAdapter?.isEnabled != true) {
                toast("Turn ON NFC in phone Settings first")
                setStatus("NFC is OFF — enable it in Settings → Connected devices → NFC")
                return@setOnClickListener
            }
            writeMode = true
            listenMode = false
            enableReaderMode()
            setStatus(
                "✍️ WRITE MODE\n\n" +
                    "Hold the NFC tag flat against the back of the phone (near the camera / centre).\n\n" +
                    "Link:\n$url"
            )
            toast("Hold tag to phone now")
            binding.listenButton.text = "Start NFC listen (read tags)"
        }

        binding.listenButton.setOnClickListener {
            if (nfcAdapter == null) {
                toast("This phone has no NFC")
                return@setOnClickListener
            }
            if (nfcAdapter?.isEnabled != true) {
                toast("Turn ON NFC in phone Settings first")
                return@setOnClickListener
            }
            writeMode = false
            listenMode = !listenMode
            if (listenMode) {
                enableReaderMode()
                binding.listenButton.text = "Stop NFC listen"
                setStatus("👂 LISTENING\n\nTap a programmed NFC tag to open its payment link.")
            } else {
                disableReaderMode()
                binding.listenButton.text = "Start NFC listen (read tags)"
                setStatus("Stopped listening")
            }
        }

        refreshPreview()
        // Cold start from tag tap while app closed
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** ReaderCallback — most reliable path on modern Android */
    override fun onTagDiscovered(tag: Tag) {
        mainHandler.post {
            try {
                if (writeMode) {
                    val url = buildPaymentUrl()
                    if (url == null) {
                        setStatus("Write cancelled — pick product / amount first")
                        writeMode = false
                        return@post
                    }
                    val result = writeUrlToTag(tag, url)
                    writeMode = false
                    if (!listenMode) disableReaderMode()
                    if (result == null) {
                        setStatus("✅ TAG WRITTEN\n\n$url\n\nTest: tap tag with another phone — POS should open.")
                        toast("Tag written OK")
                    } else {
                        setStatus("❌ WRITE FAILED\n\n$result\n\nTips:\n• Use blank NTAG213/215 stickers\n• Hold still 2 seconds\n• Tag may be locked")
                        toast("Write failed")
                    }
                    return@post
                }

                if (listenMode) {
                    val url = readUrlFromTag(tag)
                    if (url != null) {
                        setStatus("Tag read — opening\n$url")
                        openUrl(url)
                    } else {
                        val code = readTextCodeFromTag(tag)
                        if (code != null) {
                            val base = getString(R.string.pos_base_url).trimEnd('/')
                            val built = "$base/?product=${code.trim()}"
                            setStatus("Product code on tag: $code\nOpening $built")
                            openUrl(built)
                        } else {
                            setStatus("Tag has no URL/text. Program it with Write first.")
                            toast("Empty / unknown tag")
                        }
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
        // If system delivered a tag URI to us via NDEF_DISCOVERED, open it
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
            // Normalize amount for paypal.me (no trailing junk)
            val normalized = amount.toDoubleOrNull()?.let { "%.2f".format(it) } ?: amount
            return "https://paypal.me/$user/$normalized"
        }

        // POS mode
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
        // Don't toast from preview
        try {
            val base = getString(R.string.pos_base_url).trimEnd('/')
            val product = selectedProduct()
            val amountRaw = binding.amountInput.text?.toString()?.trim().orEmpty()
            val preview = if (binding.modePayPalMe.isChecked) {
                val user = getString(R.string.paypal_me_user)
                val amount = amountRaw.ifEmpty { product.priceAud?.let { "%.2f".format(it) } ?: "?" }
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

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) {
            toast("Turn NFC ON in Settings")
            return
        }
        // Reader mode is far more reliable than foreground dispatch for write/read
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
        return try {
            val uriRecord = NdefRecord.createUri(url)
            // Also store product code as text for Web NFC / simple readers
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
                    ?: return "Tag type not supported (need NTAG213/215/216)"
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
                    // createUri payload uses URI identifier code
                    return parseUriPayload(record.payload)
                }
                if (record.tnf == NdefRecord.TNF_ABSOLUTE_URI) {
                    return String(record.payload, Charsets.UTF_8)
                }
            }
            // Fallback: any text that looks like URL
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
            !adapter.isEnabled -> setStatus("NFC is OFF.\nSettings → Connected devices → Connection preferences → NFC → ON")
            else -> setStatus("NFC ready.\n1) Pick product\n2) Write tag\n3) Customer taps with their phone → POS opens")
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
        updateNfcStatusBanner()
        if (writeMode || listenMode) enableReaderMode()
        refreshPreview()
    }

    override fun onPause() {
        super.onPause()
        // Keep reader mode only while activity visible
        disableReaderMode()
    }
}

/** Tiny TextWatcher without boilerplate */
private class SimpleTextWatcher(val onChange: () -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChange()
}
