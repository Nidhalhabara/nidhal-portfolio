#!/usr/bin/env python3
"""Automated Network Monitor & Backup Tool.
Run in a loop:  python3 network_monitor.py
Run from cron:  */5 * * * * python3 /opt/netmon/network_monitor.py --once
"""
import logging, platform, re, smtplib, subprocess, sys, tarfile, time
from datetime import datetime
from email.message import EmailMessage
from pathlib import Path

CFG = {
    "hosts": {"Gateway": "192.168.1.1", "DNS": "8.8.8.8", "Server": "192.168.1.10"},
    "latency_ms": 150, "interval": 60, "alert_cooldown": 900,
    "backup_src": "/etc", "backup_dir": "/var/backups/netmon", "backup_hour": 2, "keep": 7,
    "smtp": None,  # {"host": "smtp.gmail.com", "port": 587, "user": "", "pass": "", "to": ""}
}
logging.basicConfig(filename="netmon.log", level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(message)s")
last_alert, last_backup_day = {}, None

def ping(ip):
    """Return latency in ms, or None if the host is unreachable."""
    flag = "-n" if platform.system() == "Windows" else "-c"
    try:
        out = subprocess.run(["ping", flag, "1", ip], capture_output=True, text=True, timeout=5).stdout
    except subprocess.TimeoutExpired:
        return None
    m = re.search(r"time[=<]\s*([\d.]+)", out)
    return float(m.group(1)) if m else None

def alert(key, msg):
    logging.warning(msg); print("[ALERT]", msg)
    if time.time() - last_alert.get(key, 0) < CFG["alert_cooldown"] or not CFG["smtp"]:
        return
    last_alert[key] = time.time()
    s = CFG["smtp"]; mail = EmailMessage()
    mail["Subject"], mail["From"], mail["To"] = f"[NetMon] {key}", s["user"], s["to"]
    mail.set_content(msg)
    try:
        with smtplib.SMTP(s["host"], s["port"]) as srv:
            srv.starttls(); srv.login(s["user"], s["pass"]); srv.send_message(mail)
    except Exception as e:
        logging.error("Email failed: %s", e)

def check_hosts():
    for name, ip in CFG["hosts"].items():
        ms = ping(ip)
        if ms is None:
            alert(name, f"{name} ({ip}) is OFFLINE")
        elif ms > CFG["latency_ms"]:
            alert(name, f"{name} ({ip}) high latency: {ms:.0f} ms")
        else:
            logging.info("%s (%s) OK %.0f ms", name, ip, ms)

def backup():
    global last_backup_day
    now = datetime.now()
    if now.hour != CFG["backup_hour"] or last_backup_day == now.date():
        return
    dest = Path(CFG["backup_dir"]); dest.mkdir(parents=True, exist_ok=True)
    file = dest / f"backup_{now:%Y%m%d_%H%M}.tar.gz"
    try:
        with tarfile.open(file, "w:gz") as tar:
            tar.add(CFG["backup_src"], arcname=Path(CFG["backup_src"]).name)
        last_backup_day = now.date(); logging.info("Backup created: %s", file)
        for old in sorted(dest.glob("backup_*.tar.gz"))[:-CFG["keep"]]:
            old.unlink()
    except Exception as e:
        alert("backup", f"Backup failed: {e}")

if __name__ == "__main__":
    once = "--once" in sys.argv
    while True:
        check_hosts(); backup()
        if once: break
        time.sleep(CFG["interval"])
