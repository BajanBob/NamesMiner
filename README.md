# Names Miner

Android app that finds every client in your phone's contacts whose name or surname contains an identifier (default **CL**), lists their numbers, and texts them by SMS from your own SIM, either in **blocks** (5 every 60 s by default) or as a **one-time broadcast** to everyone.

Works with any country's numbers. Nothing is uploaded anywhere; the list stays on the phone.

## Install on your phone
1. Open this repo's **Releases** page on your phone and download `NamesMiner.apk` from **latest**.
2. Open it. If asked, allow your browser or My Files to "install unknown apps".
3. Open Names Miner and allow Contacts and SMS when prompted.

Each push to `main` rebuilds the APK automatically (Actions tab → Build APK).

## Using it
1. **Find clients**: check the identifier (CL), then tap **Scan contacts**. Untick anyone who shouldn't get the message.
   - *Whole word only* matches `CL John`, `John Smith CL`, `Smith-CL` but not `CLARKE`.
   - Turn it off to match `CL` anywhere in the name (e.g. `JohnCL`).
2. **Message**: type your text; `{name}` becomes the client's first name.
3. **Send**: choose *In blocks* (size and pause adjustable) or *One-time broadcast*, then **Start sending**. It keeps going with the screen off; stop it from the notification or the Stop button.
4. **Export list** shares the list (name, number, status) as CSV to email, WhatsApp, Drive and so on.

## What the statuses mean
| Status | Meaning |
|---|---|
| Sent | Left your phone |
| Delivered | Network confirmed it reached the client's phone (carrier delivery report) |
| Confirmed | Client texted back (with "Please reply YES to confirm" switched on) |
| Failed | Not sent or rejected; press Start again to retry only the failed ones |

**About read receipts:** standard SMS has no "read" receipt that any app can request. RCS/WhatsApp read ticks aren't open to other apps. Names Miner uses the two confirmations SMS does support: carrier **delivery reports**, plus **reply-to-confirm**.

## Notes
- Android asks for permission once an app sends about 30 SMS within 30 minutes. Choose **Always allow**.
- Dual-SIM phones: messages go from your default SMS SIM (Settings › Connections › SIM manager).
- Messages also show in your normal Messages app; replies arrive there as usual.
- Some carriers don't return delivery reports. Those messages stay at *Sent* even when they arrive.
