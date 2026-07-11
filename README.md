# LeafLock NFC Tap (Android)

Staff helper app for the **Paradise Centre pop-up**.

- Pick a **fixed kit** (Family Kit, All 4, etc.)
- Or enter a **custom AUD amount**
- **Open** the live POS payment page
- **Write** that link onto an NFC tag
- **Read** tags customers / staff tap

**No root. No LSPosed / SandHook.** Uses normal Android NFC APIs.

Pairs with:  
https://leaflock-paypal-pos.onrender.com

---

## Build & install

1. Install [Android Studio](https://developer.android.com/studio)
2. **Open** this folder: `leaflock-nfc-tap`
3. Let Gradle sync
4. Plug in phone (USB debugging on) **or** use an emulator with NFC
5. Run ▶ **app**

### Optional: PayPal.me fallback

Edit `app/src/main/res/values/strings.xml`:

```xml
<string name="paypal_me_user">YourActualPayPalMeName</string>
```

Keep **POS mode** selected for kit checkout with your Render PayPal integration.

---

## Debit cards vs NFC stickers (important)

| What you tap | What happens |
|--------------|--------------|
| **Blank NTAG sticker** (written with URL) | ✅ Opens POS / PayPal.me |
| **Debit / credit contactless card** | ❌ **Cannot be read** as a product tag (EMV bank security) |
| Debit card + **“Any NFC tap”** switch | ⚠️ Only **opens your payment link** on the staff phone — does **not** charge the card |

**You cannot pull money off a customer’s debit card by “reading” it with this app.** That requires a licensed card terminal (SumUp, Square, Zeller, PayPal Zettle, etc.).

What *does* work for pop-ups:
1. Program **stickers** with `https://leaflock-paypal-pos.onrender.com/?product=family`
2. Customer taps sticker → pays in browser/PayPal  
3. Or staff use POS / PayPal.me amount on the phone

---

## NFC troubleshooting

| Problem | Fix |
|---------|-----|
| “Could not read tag” on a **debit card** | Expected — use **NTAG stickers**, not bank cards |
| Nothing happens on write | Turn **NFC ON** in phone Settings |
| Write fails | Use blank **NTAG213/215** stickers; hold still on the **back** of the phone 2s |
| Tag locked | Some cheap tags are read-only after first write — use a new sticker |
| iPhone customer | **Works** if tag has **HTTPS URL** (system opens Safari). Web NFC scan in Chrome on iPhone does **not** work |
| Web POS “Scan NFC” fails | Expected on iPhone/desktop. Use **pre-written URL tags** instead |
| Wrong product opens | Re-write tag with correct product selected |

**Reader Mode** is used (not old foreground dispatch) — more reliable on modern Android.

---

## How staff use it

### A. Program tags (once)

1. Select product e.g. **Family Kit**
2. Tap **Write this link to NFC tag**
3. Hold blank NFC sticker flat on the **back** of the phone until “TAG WRITTEN”
4. Tag now opens:  
   `https://leaflock-paypal-pos.onrender.com/?product=family`

### B. Custom amount tag

1. Select **Custom amount only**
2. Enter e.g. `45.00`
3. Write tag →  
   `https://leaflock-paypal-pos.onrender.com/?amount=45.00`

### C. Open without tag

**Open payment link now** → Chrome / POS on the same phone.

---

## Tag map (same as web POS)

| Code | Product | Price |
|------|---------|-------|
| family | Family Kit | $80 |
| all4 | Family + All 4 | $130 |
| two | 2 Flavours | $50 |
| ready | Ready Kit | $70 |
| mix1 | 1 Mix | $30 |
| extra2 | +2 Flavours | $50 |
| … | (see spinner) | … |

---

## Quick win without the app

**NFC Tools** (Play Store) → Write URL:

```text
https://leaflock-paypal-pos.onrender.com/?product=family
```

Customer taps with any modern Android phone → POS opens with Family Kit selected.

---

## Security

- Do **not** put PayPal **Client Secret** in this app
- Secrets stay on Render only
- This app only opens public HTTPS payment URLs

---

## Not included (on purpose)

- LSPosed / SandHook / root payment hooks — avoid for real money
- Card softPOS / terminal emulation — different product + licensing
