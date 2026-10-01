#!/usr/bin/env python3
"""Mini Intrusion Detection System (Scapy). Run with root: sudo python3 mini_ids.py -i eth0
Only monitor networks you own or are authorised to test. pip install scapy
"""
import argparse, logging, time
from collections import defaultdict, deque
from scapy.all import sniff, IP, TCP, ICMP, ARP

RULES = {"window": 10, "port_scan": 15, "syn_flood": 100, "icmp_flood": 50}
logging.basicConfig(filename="ids_alerts.log", level=logging.WARNING,
                    format="%(asctime)s %(levelname)s %(message)s")
ports = defaultdict(lambda: defaultdict(deque))   # src -> port -> timestamps
syns, icmps = defaultdict(deque), defaultdict(deque)
arp_table, reported = {}, {}

def trim(q, now):
    while q and now - q[0] > RULES["window"]:
        q.popleft()

def alert(kind, src, detail):
    now = time.time()
    if now - reported.get((kind, src), 0) < 60:   # one alert per minute per source
        return
    reported[(kind, src)] = now
    msg = f"{kind} from {src}: {detail}"
    print("[!]", msg); logging.warning(msg)

def inspect(pkt):
    now = time.time()
    if pkt.haslayer(ARP) and pkt[ARP].op == 2:     # ARP reply: detect spoofing
        ip, mac = pkt[ARP].psrc, pkt[ARP].hwsrc
        if arp_table.setdefault(ip, mac) != mac:
            alert("ARP_SPOOFING", ip, f"MAC changed {arp_table[ip]} -> {mac}")
    if not pkt.haslayer(IP):
        return
    src = pkt[IP].src
    if pkt.haslayer(TCP) and pkt[TCP].flags == "S":  # SYN only
        p = ports[src][pkt[TCP].dport]; p.append(now); trim(p, now)
        scanned = sum(1 for q in ports[src].values() if (trim(q, now), q)[1])
        if scanned >= RULES["port_scan"]:
            alert("PORT_SCAN", src, f"{scanned} ports in {RULES['window']}s")
        syns[src].append(now); trim(syns[src], now)
        if len(syns[src]) >= RULES["syn_flood"]:
            alert("SYN_FLOOD", src, f"{len(syns[src])} SYN packets")
    elif pkt.haslayer(ICMP) and pkt[ICMP].type == 8:
        icmps[src].append(now); trim(icmps[src], now)
        if len(icmps[src]) >= RULES["icmp_flood"]:
            alert("ICMP_FLOOD", src, f"{len(icmps[src])} echo requests")

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("-i", "--iface", default=None)
    a = ap.parse_args()
    print("IDS running... Ctrl+C to stop")
    sniff(iface=a.iface, prn=inspect, store=False)
