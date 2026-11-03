# 다트 지도 경계선 만들기: python3 -I tools/borders.py HangJeongDong_ver20261001.geojson phone-app/src/main/assets
# (vuski/admdongkor 행정동 경계 → 시·도 / 시·군·구 경계선만 남기고 단순화)
# sgg_lines.txt 줄 = "시도A|시도B|lng,lat;…" (그 선 양쪽 시·도, 바다·국경 쪽이면 B 빈칸, 같은 시·도 안의 시·군·구 경계면 A=B) → 지역 다트 지도가 그 시·도 선만 그림
# Dissolve 행정동 polygons into 시·도 / 시·군·구 boundary lines without shapely:
# an edge shared by two polygons of the same group is internal and dropped; the rest are chained and simplified.
import json, sys, collections
src, outdir = sys.argv[1], sys.argv[2]
d = json.load(open(src, encoding='utf-8'))
Q = 1e5
def key(p): return (round(p[0]*Q), round(p[1]*Q))
def rings(g):
    polys = g['coordinates'] if g['type']=='MultiPolygon' else [g['coordinates']]
    for poly in polys:
        for ring in poly: yield ring
def boundaries(groupfn):
    cnt = collections.Counter(); owner = {}
    for f in d['features']:
        grp = groupfn(f['properties'])
        for ring in rings(f['geometry']):
            ks = [key(p) for p in ring]
            for a, b in zip(ks, ks[1:]):
                if a == b: continue
                e = (a, b) if a < b else (b, a)
                cnt[(grp, e)] += 1
    # keep edges seen once within a group (outer edges of the group), with the 시·도 on each side
    edges = collections.defaultdict(list)
    for (grp, e), c in cnt.items():
        if c == 1: edges[e].append(grp.split('|')[0])
    return edges
def chain(edges):
    adj = collections.defaultdict(list)
    for a, b in edges: adj[a].append(b); adj[b].append(a)
    used = set(); lines = []
    def take(a, b):
        e = (a, b) if a < b else (b, a)
        if e in used: return False
        used.add(e); return True
    starts = [v for v, ns in adj.items() if len(ns) != 2] + list(adj.keys())
    for s in starts:
        for n in adj[s]:
            if not take(s, n): continue
            line = [s, n]; prev, cur = s, n
            while len(adj[cur]) == 2:
                nxt = adj[cur][0] if adj[cur][1] == prev else adj[cur][1]
                if not take(cur, nxt): break
                line.append(nxt); prev, cur = cur, nxt
            lines.append(line)
    return lines
def dp(pts, tol):
    if len(pts) < 3: return pts
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1-x0, y1-y0; L = (dx*dx+dy*dy) ** 0.5 or 1
    best, bi = -1, 0
    for i in range(1, len(pts)-1):
        x, y = pts[i]
        dist = abs(dy*x - dx*y + x1*y0 - y1*x0) / L if (dx or dy) else ((x-x0)**2+(y-y0)**2)**0.5
        if dist > best: best, bi = dist, i
    if best <= tol: return [pts[0], pts[-1]]
    return dp(pts[:bi+1], tol)[:-1] + dp(pts[bi:], tol)
sys.setrecursionlimit(100000)
def write(name, edges, tol, minlen, tagged=False):
    bytag = collections.defaultdict(set)
    for e, sidos in edges.items(): bytag['|'.join((sorted(sidos)[:2] + [''])[:2]) if tagged else ''].add(e)
    out = []; n = 0
    for tag, es in sorted(bytag.items()):
      for ln in chain(es):
        pts = [(x/Q, y/Q) for x, y in ln]
        s = dp(pts, tol)
        if len(s) < 2: continue
        span = max(abs(s[0][0]-p[0])+abs(s[0][1]-p[1]) for p in s)
        if len(s) == 2 and span < minlen: continue
        out.append((tag + '|' if tagged else '') + ';'.join(f'{x:.4f},{y:.4f}' for x, y in s)); n += len(s)
    open(f'{outdir}/{name}', 'w').write('\n'.join(out) + '\n')
    import os; print(name, len(out), 'lines', n, 'points', os.path.getsize(f'{outdir}/{name}'), 'bytes')
write('sido_lines.txt', boundaries(lambda p: p['sidonm']), 0.006, 0.02)
write('sgg_lines.txt', boundaries(lambda p: p['sidonm']+'|'+p['sggnm']), 0.0015, 0.004, tagged=True)
