#!/usr/bin/env python3
"""
Names Miner: email contacts compiler.

Lists every email address that sent mail TO a mailbox (e.g. admin@coopmed.com)
or received mail FROM it, with each address listed once (no duplicates,
case-insensitive).

Works with any provider, in any country:
  * IMAP (Gmail, Yahoo, Outlook.com, cPanel/hosted mail, Zoho, ...)
  * Exported mailbox files: .mbox (Google Takeout, Thunderbird, Apple Mail),
    folders of .eml files, or both.

Only message headers are read; message bodies and attachments are never downloaded.
Needs Python 3.8+ and nothing else.

Examples
  python email_contacts.py --imap imap.gmail.com --user admin@coopmed.com
  python email_contacts.py --mbox "All mail Including Spam and Trash.mbox" --target admin@coopmed.com
  python email_contacts.py --eml-dir C:\\Mail\\Exported --target admin@coopmed.com
"""
import argparse
import csv
import email
import email.utils
import getpass
import imaplib
import mailbox
import os
import re
import sys
from email.header import decode_header, make_header

HEADER_FIELDS = ["FROM", "TO", "CC", "BCC", "REPLY-TO", "DATE", "MESSAGE-ID"]
AUTOMATED = re.compile(r"(^|[._-])(no-?reply|do-?not-?reply|mailer-daemon|postmaster|bounce[s]?|notifications?)([._+-]|@)", re.I)
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def decode(value):
    if value is None:
        return ""
    try:
        return str(make_header(decode_header(str(value))))
    except Exception:
        return str(value)


class Collector:
    def __init__(self, targets, skip_automated, exclude_domains):
        self.targets = {t.lower() for t in targets}
        self.skip_automated = skip_automated
        self.exclude_domains = {d.lower().lstrip("@") for d in exclude_domains}
        self.people = {}          # email -> record
        self.seen_ids = set()     # Message-IDs already counted (same mail in several folders)
        self.messages_checked = 0
        self.messages_matched = 0

    def _keep(self, addr):
        if not EMAIL_RE.match(addr) or addr in self.targets:
            return False
        if addr.split("@", 1)[1] in self.exclude_domains:
            return False
        if self.skip_automated and AUTOMATED.search(addr):
            return False
        return True

    def _add(self, name, addr, direction, when):
        rec = self.people.setdefault(addr, {"email": addr, "name": "", "sent_to": 0, "received_from": 0,
                                            "first_seen": None, "last_seen": None})
        if name and not rec["name"]:
            rec["name"] = name
        rec[direction] += 1
        if when:
            if rec["first_seen"] is None or when < rec["first_seen"]:
                rec["first_seen"] = when
            if rec["last_seen"] is None or when > rec["last_seen"]:
                rec["last_seen"] = when

    def add_message(self, msg):
        self.messages_checked += 1
        mid = (msg.get("Message-ID") or "").strip()
        if mid:
            if mid in self.seen_ids:
                return
            self.seen_ids.add(mid)

        def addrs(*fields):
            vals = [decode(v) for f in fields for v in (msg.get_all(f) or [])]
            out = []
            for name, addr in email.utils.getaddresses(vals):
                addr = addr.strip().strip("<>").lower()
                if addr:
                    out.append((decode(name).strip().strip('"'), addr))
            return out

        senders = addrs("From")
        recipients = addrs("To", "Cc", "Bcc")
        reply_to = addrs("Reply-To")
        from_target = any(a in self.targets for _, a in senders)
        to_target = any(a in self.targets for _, a in recipients)
        if not (from_target or to_target):
            return
        self.messages_matched += 1

        when = None
        try:
            d = email.utils.parsedate_to_datetime(msg.get("Date"))
            when = d.replace(tzinfo=None) if d else None
        except Exception:
            pass

        if from_target:
            for name, addr in recipients:
                if self._keep(addr):
                    self._add(name, addr, "sent_to", when)
        if to_target:
            for name, addr in senders + reply_to:
                if self._keep(addr):
                    self._add(name, addr, "received_from", when)

    def write(self, out_csv):
        rows = sorted(self.people.values(), key=lambda r: (-(r["sent_to"] + r["received_from"]), r["email"]))
        with open(out_csv, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            w.writerow(["Email", "Name", "Emails sent to them", "Emails received from them", "Total", "First seen", "Last seen"])
            for r in rows:
                w.writerow([r["email"], r["name"], r["sent_to"], r["received_from"], r["sent_to"] + r["received_from"],
                            r["first_seen"].strftime("%Y-%m-%d") if r["first_seen"] else "",
                            r["last_seen"].strftime("%Y-%m-%d") if r["last_seen"] else ""])
        txt = os.path.splitext(out_csv)[0] + ".txt"
        with open(txt, "w", encoding="utf-8") as f:
            f.write("\n".join(r["email"] for r in rows) + ("\n" if rows else ""))
        return rows, txt


# ---------------- sources ----------------

def scan_mbox(path, col):
    print(f"Reading {path} …")
    for i, msg in enumerate(mailbox.mbox(path, create=False)):
        col.add_message(msg)
        if i and i % 5000 == 0:
            print(f"  {i} messages…")


def scan_eml_dir(folder, col):
    print(f"Reading .eml files in {folder} …")
    for root, _, files in os.walk(folder):
        for fn in files:
            if fn.lower().endswith(".eml"):
                with open(os.path.join(root, fn), "rb") as fh:
                    col.add_message(email.message_from_binary_file(fh))


LIST_RE = re.compile(r'\((?P<flags>[^)]*)\)\s+(?P<delim>"[^"]*"|NIL)\s+(?P<name>.+)')


def scan_imap(host, port, user, password, col, folders=None, use_ssl=True):
    print(f"Connecting to {host} as {user} …")
    conn = imaplib.IMAP4_SSL(host, port) if use_ssl else imaplib.IMAP4(host, port)
    conn.login(user, password)
    try:
        if not folders:
            typ, data = conn.list()
            folders = []
            for raw in data or []:
                line = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)
                m = LIST_RE.match(line)
                if not m or "\\noselect" in m.group("flags").lower():
                    continue
                folders.append(m.group("name").strip())
        for folder in folders:
            quoted = folder if folder.startswith('"') else '"' + folder.replace('"', '\\"') + '"'
            typ, _ = conn.select(quoted, readonly=True)
            if typ != "OK":
                print(f"  skipped {folder}")
                continue
            uids = []
            for t in col.targets:
                for crit in (f'FROM "{t}"', f'TO "{t}"', f'CC "{t}"', f'BCC "{t}"'):
                    try:
                        typ, data = conn.uid("SEARCH", None, crit)
                        if typ == "OK" and data and data[0]:
                            uids.extend(data[0].split())
                    except imaplib.IMAP4.error:
                        pass
            uids = sorted(set(uids), key=int)
            print(f"  {folder}: {len(uids)} matching messages")
            fields = " ".join(HEADER_FIELDS)
            for i in range(0, len(uids), 300):
                chunk = b",".join(uids[i:i + 300]).decode()
                typ, data = conn.uid("FETCH", chunk, f"(BODY.PEEK[HEADER.FIELDS ({fields})])")
                if typ != "OK":
                    continue
                for part in data:
                    if isinstance(part, tuple) and len(part) > 1:
                        col.add_message(email.message_from_bytes(part[1]))
    finally:
        try:
            conn.logout()
        except Exception:
            pass


def fail(message, interactive):
    print("\n" + message)
    if interactive:
        input("\nPress Enter to close.")
    sys.exit(1)


def interactive_args():
    """Ask simple questions when started by double-click (no arguments)."""
    print("Names Miner: email contacts compiler\n")
    target = input("Mailbox to analyse [admin@coopmed.com]: ").strip() or "admin@coopmed.com"
    print("\nWhere is the mail?\n  1) Connect to the mailbox (IMAP)\n  2) An exported .mbox file\n  3) A folder of .eml files")
    choice = input("Choose 1, 2 or 3 [1]: ").strip() or "1"
    args = ["--target", target]
    if choice == "2":
        args += ["--mbox", input("Path to the .mbox file: ").strip().strip('"')]
    elif choice == "3":
        args += ["--eml-dir", input("Path to the folder: ").strip().strip('"')]
    else:
        print("\nCommon servers: imap.gmail.com · imap.mail.yahoo.com · outlook.office365.com · mail.<yourdomain>")
        args += ["--imap", input("IMAP server: ").strip(), "--user", input(f"Login [{target}]: ").strip() or target]
    if (input("\nLeave out no-reply and automated senders? [Y/n]: ").strip().lower() or "y").startswith("y"):
        args.append("--skip-automated")
    return args


def main():
    interactive = len(sys.argv) == 1
    if interactive:
        sys.argv += interactive_args()
    ap = argparse.ArgumentParser(description="List every address that emailed, or was emailed by, a mailbox. No duplicates.")
    ap.add_argument("--target", action="append", help="Mailbox to analyse (repeatable). Default: --user, else admin@coopmed.com")
    ap.add_argument("--imap", metavar="HOST", help="IMAP server, e.g. imap.gmail.com, imap.mail.yahoo.com, outlook.office365.com")
    ap.add_argument("--port", type=int, default=993)
    ap.add_argument("--no-ssl", action="store_true", help="Plain IMAP on port 143 (not recommended)")
    ap.add_argument("--user", help="IMAP login (usually the full email address)")
    ap.add_argument("--folder", action="append", help="Only these IMAP folders (repeatable). Default: all folders")
    ap.add_argument("--mbox", action="append", default=[], help="Exported .mbox file (repeatable)")
    ap.add_argument("--eml-dir", action="append", default=[], help="Folder of .eml files (repeatable)")
    ap.add_argument("--skip-automated", action="store_true", help="Leave out no-reply, mailer-daemon, bounce and notification addresses")
    ap.add_argument("--exclude-domain", action="append", default=[], help="Leave out a domain, e.g. coopmed.com for external contacts only")
    ap.add_argument("--out", default="coopmed-email-contacts.csv", help="Output CSV (a .txt list is written beside it)")
    a = ap.parse_args()

    if not (a.imap or a.mbox or a.eml_dir):
        ap.error("give at least one source: --imap HOST, --mbox FILE or --eml-dir FOLDER")
    targets = a.target or [a.user or "admin@coopmed.com"]
    col = Collector(targets, a.skip_automated, a.exclude_domain)

    if a.imap:
        user = a.user or targets[0]
        password = os.environ.get("NM_IMAP_PASSWORD") or getpass.getpass(f"Password or app password for {user}: ")
        try:
            scan_imap(a.imap, 143 if a.no_ssl and a.port == 993 else a.port, user, password, col, a.folder, not a.no_ssl)
        except imaplib.IMAP4.error as e:
            fail(f"IMAP error: {e}\nTip: Gmail and Yahoo need an app password; Microsoft 365 may need IMAP enabled by the admin.", interactive)
        except OSError as e:
            fail(f"Couldn't reach {a.imap}: {e}", interactive)
    for p in a.mbox:
        if not os.path.isfile(p):
            fail(f"Can't find the mailbox file: {p}", interactive)
        scan_mbox(p, col)
    for d in a.eml_dir:
        if not os.path.isdir(d):
            fail(f"Can't find the folder: {d}", interactive)
        scan_eml_dir(d, col)

    rows, txt = col.write(a.out)
    print(f"\nChecked {col.messages_checked} messages, {col.messages_matched} involved {', '.join(sorted(col.targets))}.")
    print(f"{len(rows)} unique email addresses written to:\n  {os.path.abspath(a.out)}\n  {os.path.abspath(txt)}")
    if interactive:
        input("\nPress Enter to close.")


if __name__ == "__main__":
    main()
