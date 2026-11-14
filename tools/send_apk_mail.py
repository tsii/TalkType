#!/usr/bin/env python3
"""Send a finished APK by e-mail directly through the recipient domain's SMTP server.

No mail client, no relay account: the message is handed to the MX host of the
recipient domain (resolved with dig) on port 25, MIME-encoded (RFC 2045/2046),
base64 line-chunked to 76 chars.

Usage:
    python3 tools/send_apk_mail.py --to ts@ii.de --apk dist/talktype-0.1.49.apk \
        --version 0.1.49 --note "..."

Dry run (envelope only, no DATA, nothing leaves the box):
    python3 tools/send_apk_mail.py --to ts@ii.de --apk ... --dry-run
"""
from __future__ import annotations

import argparse
import hashlib
import mimetypes
import os
import smtplib
import socket
import ssl
import subprocess
import sys
from email.generator import BytesGenerator
from email.message import EmailMessage
from email.utils import formatdate, make_msgid
from io import BytesIO


def mx_host(domain: str) -> str:
    """Lowest-preference MX of the domain (falls back to the domain itself)."""
    try:
        out = subprocess.run(
            ["dig", "+short", "MX", domain], capture_output=True, text=True, timeout=20
        ).stdout.split()
        pairs = [(int(out[i]), out[i + 1].rstrip(".")) for i in range(0, len(out) - 1, 2)]
        if pairs:
            return sorted(pairs)[0][1]
    except Exception:  # noqa: BLE001 - best effort, fallback below
        pass
    return domain


def build_message(to: str, sender: str, subject: str, note: str, apk: str,
                  version: str, sha256: str) -> EmailMessage:
    msg = EmailMessage()
    msg["From"] = sender
    msg["To"] = to
    msg["Subject"] = subject
    msg["Date"] = formatdate(localtime=True)
    msg["Message-ID"] = make_msgid(domain=sender.split("@")[-1])
    msg["X-TalkType-Version"] = version
    msg["X-TalkType-SHA256"] = sha256

    msg.set_content(
        f"{note}\n\n"
        f"Datei: {os.path.basename(apk)}\n"
        f"Version: {version}\n"
        f"Groesse: {os.path.getsize(apk) / 1048576:.1f} MB\n"
        f"SHA256: {sha256}\n\n"
        "Installation auf dem Telefon (gleiche Signatur, Daten bleiben erhalten):\n"
        f"  adb install -r {os.path.basename(apk)}\n"
    )

    ctype, _ = mimetypes.guess_type(apk)
    with open(apk, "rb") as fh:
        data = fh.read()
    msg.add_attachment(
        data,
        maintype=(ctype or "application/octet-stream").split("/")[0],
        subtype=(ctype or "application/octet-stream").split("/")[1],
        filename=os.path.basename(apk),
    )
    return msg


def serialize(msg: EmailMessage) -> bytes:
    buf = BytesIO()
    BytesGenerator(buf, mangle_from_=False, maxheaderlen=0).flatten(msg)
    return buf.getvalue()


def send(host: str, port: int, sender: str, to: str, payload: bytes,
         login: tuple[str, str] | None, tls: str | None, dry_run: bool) -> int:
    print(f"[smtp] {host}:{port} -> {to} ({len(payload) / 1048576:.1f} MB raw MIME)")
    if tls == "starttls":
        client: smtplib.SMTP = smtplib.SMTP(host, port, timeout=180)
        client.ehlo()
        client.starttls(context=ssl.create_default_context())
        client.ehlo()
    else:
        client = smtplib.SMTP(host, port, timeout=180)
        client.ehlo()

    try:
        code, resp = client.mail(sender)
        print(f"[smtp] MAIL FROM {sender} -> {code} {resp.decode(errors='replace')[:80]}")
        if code != 250:
            return 2
        code, resp = client.rcpt(to)
        print(f"[smtp] RCPT TO {to} -> {code} {resp.decode(errors='replace')[:80]}")
        if code not in (250, 251):
            return 3
        if dry_run:
            print("[smtp] dry-run: RSET, kein DATA")
            client.rset()
            return 0
        if login:
            client.login(*login)
        code, resp = client.data(payload)
        print(f"[smtp] DATA -> {code} {resp.decode(errors='replace')[:120]}")
        return 0 if code == 250 else 4
    finally:
        try:
            client.quit()
        except Exception:  # noqa: BLE001
            pass


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--to", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--version", default="unbekannt")
    ap.add_argument("--note", default="Anbei das fertige TalkType-APK (Release-Build).")
    ap.add_argument("--from", dest="sender", default=None,
                    help="Envelope/From address (default: the recipient address)")
    ap.add_argument("--port", type=int, default=25)
    ap.add_argument("--host", default=None, help="SMTP host (default: MX of --to)")
    ap.add_argument("--starttls", action="store_true")
    ap.add_argument("--user", default=None)
    ap.add_argument("--password", default=None)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.apk):
        print(f"APK fehlt: {args.apk}", file=sys.stderr)
        return 1
    sha = hashlib.sha256()
    with open(args.apk, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            sha.update(chunk)
    digest = sha.hexdigest()

    domain = args.to.split("@")[-1]
    host = args.host or mx_host(domain)
    sender = args.sender or args.to

    msg = build_message(args.to, sender,
                        f"TalkType {args.version} — APK", args.note,
                        args.apk, args.version, digest)
    payload = serialize(msg)
    print(f"[mime] {len(payload) / 1048576:.1f} MB, sha256={digest[:16]}…")

    login = (args.user, args.password) if args.user and args.password else None
    tls = "starttls" if args.starttls else None
    try:
        return send(host, args.port, sender, args.to, payload, login, tls, args.dry_run)
    except (socket.timeout, socket.error, smtplib.SMTPException) as exc:
        print(f"[smtp] FEHLER: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 5


if __name__ == "__main__":
    sys.exit(main())
