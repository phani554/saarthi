# Playbook: WhatsApp Calls & Message Forwarding

## Calls (voice or video)
Call `place_call(contact="<contact_name>", call_type="whatsapp_voice")` (or `"whatsapp_video"`, or `"phone"`).
place_call asks the user first. If it returns "CONFIRMATION NEEDED", stop: Saathi asks the user and places the
call only after they say yes. Never open WhatsApp and tap the Phone or Camera icon yourself (those taps are blocked).

## Message Forwarding
Forwarding is not supported. Use `send_message` with the text instead; it also asks the user before sending.
