# Use YOUR phone as Tap to Pay (customer taps their debit card)

## Hard truth

| What people want | What actually works |
|------------------|---------------------|
| Customer taps **debit/credit card** on **your phone** → money arrives | **Licensed SoftPOS / Tap to Pay** app from a bank-grade provider |
| DIY app “reads card and charges PayPal” | **Illegal / impossible** without PCI + EMV certification |

Bank cards use **EMV**. Only Google-certified **Tap to Pay on Android** partners can turn a phone into a terminal.

Our LeafLock NFC app **cannot** charge cards by itself. It can:
- Open **Stripe / Square / PayPal POS** for real Tap to Pay  
- Program **stickers** for POS / PayPal.me links  

---

## Fastest path for Paradise Centre (Australia)

### Option A — Stripe (recommended if you can open Stripe AU)

1. Create account: https://stripe.com/au  
2. Install **Stripe Dashboard** on Android:  
   https://play.google.com/store/apps/details?id=com.stripe.android.dashboard  
3. Enable **Tap to Pay on Android** (compatible NFC phone + Play Services)  
4. At the stand: enter amount (e.g. $80 Family Kit) → **Tap to Pay** → customer taps card on **back of your phone**  
5. Supports Visa/MC/Amex contactless + Google Pay / Apple Pay + **eftpos** (AU)

Docs: https://stripe.com/terminal/tap-to-pay  

### Option B — Square Australia

1. Sign up Square AU  
2. Install **Square Point of Sale**  
3. Enable **Tap to Pay on this device**  
4. Charge → customer taps card  

### Option C — PayPal POS (ex Zettle)

1. Install **PayPal POS**: https://play.google.com/store/apps/details?id=com.izettle.android  
2. Sign in with business PayPal  
3. Check if **Tap to Pay** is enabled for **Australia** (varies by country)  
4. If not offered in AU → use **Stripe** or **Square**

Your `paypal.me/LeafLockAU` link is for **customer-pays-on-their-phone**, not for card-on-your-phone.

---

## What to do at the pop-up

### Card tap on **your** phone
1. Open **Stripe Dashboard** or **Square**  
2. Enter kit price ($80 / $130 / custom)  
3. Tap to Pay → customer holds card/phone to **your** phone  

### Link / sticker flow (already built)
1. Write NTAG stickers with POS URL  
2. Customer taps sticker with **their** phone  
3. Pays in browser / PayPal  

---

## Fees

SoftPOS providers take a **% per transaction** (typical retail rates). There is no free legal DIY card-tap.

---

## Do not use

- LSPosed / SandHook / Magisk “payment hooks”  
- Apps that claim to “skim” or “soft POS” without a known bank partner  
- Reading full card numbers offline  

Those risk stolen funds, banned accounts, and legal issues.
