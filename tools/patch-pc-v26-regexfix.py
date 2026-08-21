from pathlib import Path
p=Path('pc-relay/roon_netshare_relay_v3.py')
s=p.read_text(encoding='utf-8')
lines=s.splitlines()
changed=False
for i,line in enumerate(lines):
    if 'for m in re.finditer' in line and 'port[A-Za-z0-9_.-]*' in line:
        lines[i]='        for m in re.finditer(r"(?i)([A-Za-z0-9_.-]*port[A-Za-z0-9_.-]*)[\\\"\'\\s]*[:=][\\\"\'\\s]*(\\d{4,5})",text):'
        changed=True
if not changed:raise SystemExit('v2.6 regex line not found')
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
print('PC Relay v2.6 regex quoting fix applied')
