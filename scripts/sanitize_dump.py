"""Sanitize a uiautomator dump for use as a test fixture.

Every text / content-desc / hint value is replaced with a placeholder, so no chat
content, nickname or avatar description survives. Message nodes (by view-id
name, given as argv[1]) become 消息1..N numbered top-to-bottom; everything else
becomes 文本1..N. Structure, ids, classes and bounds are kept verbatim.
Reads raw XML on stdin, writes sanitized XML to stdout. Raw input is never saved.
"""
import re, sys
import xml.etree.ElementTree as ET

msg_id = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
nodes = list(root.iter('node'))

def top(n):
    m = re.match(r'\[(-?\d+),(-?\d+)\]', n.get('bounds', '[0,0]'))
    return int(m.group(2)) if m else 0

msgs = sorted((n for n in nodes if n.get('resource-id', '').split('/')[-1] == msg_id
               and (n.get('text') or n.get('content-desc'))), key=top)
msg_name = {id(n): f'消息{i+1}' for i, n in enumerate(msgs)}
other = 0
for n in nodes:
    for attr in ('text', 'content-desc', 'hint'):
        if n.get(attr):
            if id(n) in msg_name and attr in ('text', 'content-desc'):
                n.set(attr, msg_name[id(n)])
            else:
                other += 1
                n.set(attr, f'文本{other}')
sys.stdout.write(ET.tostring(root, encoding='unicode'))
