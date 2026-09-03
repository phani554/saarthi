# Playbook: WhatsApp Calls & Message Forwarding

Follow these steps for WhatsApp Voice Call, Video Call, and Message Forwarding:

## Step 1: Contact & Chat Resolution
1. Open WhatsApp (`open_app(package_name="com.whatsapp")`).
2. Search for the target contact or group using `find_search_bar(query="<contact_name>")` or `input_text`.
3. Tap the contact row on the right text area (65% X offset) to open chat.

## Step 2: Chat Verification Guard
1. Call `get_screen_info` to inspect the top action bar header.
2. Verify that the contact/group display name matches the user's intended target contact.
3. If the chat header does NOT match, tap back key and re-search.

## Step 3: Execution & Call Retention
- **For WhatsApp Voice Call**: Tap the Phone icon in the top header action bar. Once call screen is active, call `finish(summary="Voice call placed to <contact>.")`.
- **For WhatsApp Video Call**: Tap the Camera icon in the top header action bar. Once video call screen is active, call `finish(summary="Video call placed to <contact>.")`.
- **For Message Forwarding**: Long-press message -> tap Forward icon -> select target contact -> tap Send. Verify delivery and call `finish`.
