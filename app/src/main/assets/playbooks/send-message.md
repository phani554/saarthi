---
id: send_message
name: Send Message
triggers:
  - "send"
  - "tell"
  - "reply"
  - "message"
  - "text"
---

When the user wants to send a message to someone else:

1. **send_message** → call send_message(contact="[person name]", message="[what to say]", app="[app name, default WhatsApp]")
2. **finish** → call finish(summary="Sent '[message]' to [contact] on [app]")

send_message asks the user first ("Ravi ko '...' bhejun? Haan ya na?"). If it returns "CONFIRMATION NEEDED", stop:
Saathi sends only after the user says yes. Never type into a chat and tap Send yourself (that tap is blocked).

Extract from the user's request:
- contact = the person's name (e.g. "Mom", "Girlfriend", "John")
- message = what to send, exactly in the user's own words and language, never translated (e.g. "hi", "main aa rahi hoon")
- app = which app (default "WhatsApp" if not specified. Use "Telegram", "Messages" if mentioned)

Only use this playbook when the user clearly wants delivery to another person.
- Good: `send hi to Mom`, `tell Alice I'll be late`, `message John on Telegram`
- Not this playbook: `say hi`, `hi`, `tell me more`, `say that again`, `what do you think?`
