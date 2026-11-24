# Names Miner

Android app that finds every client in your phone's contacts tagged **CL** or **Coopmed** (any case, as a separate word in the first name or surname), lists their numbers, and texts them by SMS from your own SIM, either in **blocks** (5 every 60 s by default) or as a **one-time broadcast** to everyone.

Works with any country's numbers. Nothing is uploaded anywhere; the list stays on the phone.

## Install on your phone
1. Open this repo's **Releases** page on your phone and download `NamesMiner.apk` from **latest**.
2. Open it. If asked, allow your browser or My Files to "install unknown apps".
3. Open Names Miner and allow Contacts and SMS when prompted.

Each push to `main` rebuilds the APK automatically (Actions tab → Build APK).

## Using it
1. **Find clients**: the tags box holds `CL, Coopmed` (add more, separated by commas). Tap **Scan contacts**, then untick anyone who shouldn't get the message.
   - Matches a tag as its own word, in any case: `CL John`, `Mary cl`, `cL Smith`, `Smith-CL`, `C.L. Brown`, `Coopmed Ann`.
   - Ignores names that just contain the letters: `Clarke`, `Michael`, `Clyde`, `Coopmedic`.
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

## Desktop and web versions (Windows, Linux, any browser)
Computers can't send SMS from your phone's SIM, so the desktop version works from a contacts export and sends through **WhatsApp**:
1. Export contacts from the phone (Contacts › ☰ › Manage contacts › Import or export › Export to a .vcf) or from contacts.google.com (vCard or Google CSV), and copy the file to the computer.
2. Open Names Miner and drop the file in. Tags work as on Android (CL, Coopmed, any case, whole words).
3. Set the country calling code for numbers saved without a "+", type the message, and press **Open next chat**. Each chat opens in WhatsApp Desktop or WhatsApp Web with the message typed; press Send, then come back for the next one. A pause follows each block (default 5, 60 s).

Downloads (Releases › latest): `NamesMiner-Windows-Setup.exe`, `NamesMiner-Windows-Portable.exe`, `NamesMiner-Windows.zip`, `NamesMiner-Linux.AppImage`, `.deb`, `.tar.gz`, and `NamesMiner-Web.html` (open in any browser).

Windows may show "Windows protected your PC" because the app isn't code-signed: click **More info › Run anyway**.

## Good-neighbour protocol (v1.2)
- **Small blocks, long gaps:** default 5 messages, then a 20-minute pause, until everyone selected has been sent. Applies on Android (automatic) and desktop (the button locks, and a notification says when the next block is ready).
- **Good-neighbour hours:** nothing is sent outside 08:00–20:00 local time (adjustable). A run that reaches the evening resumes the next morning.
- **No duplicates:** every number gets each message once. Rescanning, resetting statuses or restarting can't resend the same text to the same number. A changed message counts as a new message.
- **Opt-outs:** on Android, a reply of STOP, UNSUBSCRIBE, CANCEL, END, QUIT, OPT OUT or REMOVE excludes that number from all future sends. On desktop, set the client's status to "Opted out".
- **Report:** shows who didn't get it (failed or not on WhatsApp), who hasn't opened or seen it yet, who replied, opt-outs, blocked duplicates and who's still waiting, with plain-language next steps. You can share or export it.

What can be known: SMS gives *delivered* and *replied*, never *read*. On personal WhatsApp, the app can't see ticks, so you set each status from what WhatsApp shows. Automatic read tracking needs the WhatsApp Business Platform.

On Android, allow background running (the app asks) and set Settings › Apps › Names Miner › Battery to **Unrestricted**, so multi-hour runs aren't paused.
