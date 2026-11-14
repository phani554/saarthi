# Care profile and SOS

Open: Settings → Care profile & SOS (or the red SOS in the chat toolbar).

## What it stores (on the phone, MMKV key KEY_CARE_PROFILE_V1)
- Medicines (from prescription photos, editable), medical history (conditions, allergies, surgeries)
- Building watchman, family doctor, chemist (one each), any number of loved ones
- SOS call order

## Prescription upload
Photo → Gemini vision (uses the Gemini key in Settings, header auth) → shows medicines, doctor, date,
conditions and anything unclear → saved only after "Save". Always check names against the paper.
The prescribing doctor becomes the family doctor only if none is saved.

## Remembering from conversation
- Offline fast path: a sentence with a role word and a phone number ("watchman ka number 98xxxxxxxx hai").
- AI path (background): extracts contacts, conditions and medicines from Hindi/Telugu/English speech and
  writes them into the care profile. Spoken voice turns are learned too.
- The agent sees a short care section in its prompt (names and roles, never numbers).

## Messaging
Tool `message_care_contact(role, message)` WhatsApps the saved number via wa.me (no phone-book name
needed). "Order my medicines from the chemist" sends the saved medicine list.

## SOS
Triggers: SOS button (chat: tap twice), voice ("bachao", "help me", "emergency", "madad", "kapadandi"...,
but not "add emergency contact"), or the agent's send_distress_signal tool.
1. SMS to everyone on the list except the doctor (needs SEND_SMS).
2. Calls in order (needs CALL_PHONE; else opens the dialer for the first one only).
3. A call that ends within 60 s counts as unanswered → next person. Android doesn't tell apps whether an
   outgoing call was answered, so this errs on calling one person too many.
4. "Stop" / "theek hoon" / Stop SOS ends the chain. Gives up after 15 minutes.
Permissions requested on the care screen: CALL_PHONE, SEND_SMS, READ_PHONE_STATE.

Not compiled or run yet.
