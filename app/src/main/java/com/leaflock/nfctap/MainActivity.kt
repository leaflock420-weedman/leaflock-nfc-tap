package com.leaflock.nfctap

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.leaflock.nfctap.databinding.ActivityMainBinding
import java.nio.charset.Charset

/**
 * LeafLock staff NFC helper:
 * - Build POS links for fixed kits or custom AUD amounts
 * - Write links onto NFC tags
 * - Read tags and open the POS / URL
 * - Optional PayPal.me fallback
 *
 * No root. No LSPosed/SandHook.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var listening = false
    private var writeMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            toast("This phone has no NFC")
            binding.statusText.text = "NFC not supported on this device"
        } else if (nfcAdapter?.isEnabled == false) {
            binding.statusText.text = "Turn NFC on in system settings"
        }

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

        binding.openLinkButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            openUrl(url)
            binding.statusText.text = "Opened payment link"
        }

        binding.writeTagButton.setOnClickListener {
            val url = buildPaymentUrl() ?: return@setOnClickListener
            writeMode = true
            listening = true
            enableForeground()
            binding.statusText.text = "WRITE MODE — hold tag to phone now\n$url"
            toast("Hold blank/writable NFC tag to the back of the phone")
        }

        binding.listenButton.setOnClickListener {
            writeMode = false
            listening = !listening
            if (listening) {
                enableForeground()
                binding.listenButton.text = "Stop NFC listen"
                binding.statusText.text = "Listening — tap a tag to open its link"
            } else {
                disableForeground()
                binding.listenButton.text = "Start NFC listen (read tags)"
                binding.statusText.text = "Stopped"
            }
        }

        refreshPreview()
        handleIntent(intent)
    }

    private fun selectedProduct(): PosProduct =
        binding.productSpinner.selectedItem as PosProduct

    private fun buildPaymentUrl(): String? {
        val base = getString(R.string.pos_base_url).trimEnd('/')
        val product = selectedProduct()
        val amountRaw = binding.amountInput.text?.toString()?.trim().orEmpty()

        if (binding.modePayPalMe.isChecked) {
            val user = getString(R.string.paypal_me_user).trim()
            if (user.isEmpty() || user == "YOUR_PAYPAL_ME_USERNAME") {
                toast("Set paypal_me_user in strings.xml first")
                return null
            }
            val amount = amountRaw.ifEmpty {
                product.priceAud?.let { "%.2f".format(it) } ?: ""
            }
            if (amount.isEmpty()) {
                toast("Enter an amount for PayPal.me")
                return null
            }
            return "https://paypal.me/$user/$amount"
        }

        // POS mode (recommended)
        return when {
            product.code == "custom" || amountRaw.isNotEmpty() && product.code == "custom" -> {
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
        binding.linkPreview.text = buildPaymentUrl() ?: "Select product / amount"
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun enableForeground() {
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0, intent, flags)
        adapter.enableForegroundDispatch(this, pi, null, null)
    }

    private fun disableForeground() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (_: Exception) {
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) return

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag == null) return

        if (writeMode) {
            val url = buildPaymentUrl() ?: return
            val ok = writeUrlToTag(tag, url)
            writeMode = false
            if (!listening) disableForeground()
            if (ok) {
                binding.statusText.text = "Tag written ✓\n$url"
                toast("NFC tag written")
            } else {
                binding.statusText.text = "Write failed — tag locked or not writable?"
                toast("Could not write tag")
            }
            return
        }

        // Read mode
        val url = readUrlFromTag(tag)
        if (url != null) {
            binding.statusText.text = "Tag read — opening\n$url"
            openUrl(url)
        } else {
            // Fallback: open currently selected POS product
            val fallback = buildPaymentUrl()
            if (fallback != null) {
                binding.statusText.text = "No URL on tag — opening selected product"
                openUrl(fallback)
            } else {
                toast("No URL on tag")
            }
        }
    }

    private fun readUrlFromTag(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val msg = ndef.ndefMessage ?: ndef.cachedNdefMessage
            ndef.close()
            if (msg == null) return null
            for (record in msg.records) {
                when (record.tnf) {
                    NdefRecord.TNF_WELL_KNOWN -> {
                        if (record.type.contentEquals(NdefRecord.RTD_URI)) {
                            return parseUriRecord(record.payload)
                        }
                        if (record.type.contentEquals(NdefRecord.RTD_TEXT)) {
                            val text = parseTextRecord(record.payload)
                            if (text.startsWith("http")) return text
                            // product code on tag e.g. "family"
                            val base = getString(R.string.pos_base_url).trimEnd('/')
                            return "$base/?product=${text.trim()}"
                        }
                    }
                    NdefRecord.TNF_ABSOLUTE_URI -> {
                        return String(record.payload, Charset.forName("UTF-8"))
                    }
                }
            }
            null
        } catch (e: Exception) {
            toast("Read error: ${e.message}")
            null
        }
    }

    private fun parseTextRecord(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val langLen = payload[0].toInt() and 0x3F
        return String(payload, 1 + langLen, payload.size - 1 - langLen, Charsets.UTF_8)
    }

    private fun parseUriRecord(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefixCode = payload[0].toInt() and 0xFF
        val prefixes = arrayOf(
            "", "http://www.", "https://www.", "http://", "https://",
            "tel:", "mailto:", "ftp://anonymous:anonymous@", "ftp://ftp.",
            "ftps://", "sftp://", "smb://", "nfs://", "ftp://", "dav://",
            "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:",
            "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
            "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
            "urn:epc:raw:", "urn:epc:", "urn:nfc:"
        )
        val prefix = if (prefixCode < prefixes.size) prefixes[prefixCode] else ""
        val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
        return prefix + rest
    }

    private fun writeUrlToTag(tag: Tag, url: String): Boolean {
        return try {
            val record = NdefRecord.createUri(url)
            val message = NdefMessage(arrayOf(record))
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    ndef.close()
                    return false
                }
                if (ndef.maxSize < message.toByteArray().size) {
                    ndef.close()
                    return false
                }
                ndef.writeNdefMessage(message)
                ndef.close()
                true
            } else {
                val format = NdefFormatable.get(tag) ?: return false
                format.connect()
                format.format(message)
                format.close()
                true
            }
        } catch (e: Exception) {
            toast("Write error: ${e.message}")
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (listening || writeMode) enableForeground()
        refreshPreview()
    }

    override fun onPause() {
        super.onPause()
        disableForeground()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
