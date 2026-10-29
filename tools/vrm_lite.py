#!/usr/bin/env python3
"""Shrink a VRM for low-end devices: keep only the morph targets used by VRM expressions, downscale textures.
Usage: vrm_lite.py in.vrm out.vrm [--max-tex 1024]"""
import struct, json, sys, io
from PIL import Image

def read_glb(p):
    d = open(p, 'rb').read()
    cl, _ = struct.unpack_from('<II', d, 12); j = json.loads(d[20:20 + cl])
    o = 20 + cl; bl, _ = struct.unpack_from('<II', d, o); return j, d[o + 8:o + 8 + bl]

def write_glb(p, j, b):
    js = json.dumps(j, separators=(',', ':')).encode(); js += b' ' * (-len(js) % 4); b += b'\0' * (-len(b) % 4)
    with open(p, 'wb') as f:
        f.write(struct.pack('<4sII', b'glTF', 2, 28 + len(js) + len(b)))
        f.write(struct.pack('<II', len(js), 0x4E4F534A) + js); f.write(struct.pack('<II', len(b), 0x004E4942) + b)

def main(src, dst, max_tex=1024):
    j, b = read_glb(src); ext = j['extensions']['VRMC_vrm']
    # 1. morph targets referenced by any expression
    keep = set()
    exprs = list(ext['expressions'].get('preset', {}).values()) + list(ext['expressions'].get('custom', {}).values())
    for e in exprs:
        for bd in e.get('morphTargetBinds', []): keep.add(bd['index'])
    keep = sorted(keep); remap = {o: n for n, o in enumerate(keep)}
    for e in exprs:
        for bd in e.get('morphTargetBinds', []): bd['index'] = remap[bd['index']]
    for m in j['meshes']:
        for p in m['primitives']:
            if 'targets' in p: p['targets'] = [p['targets'][i] for i in keep if i < len(p['targets'])]
            if 'extras' in p and 'targetNames' in p['extras']: p['extras']['targetNames'] = [p['extras']['targetNames'][i] for i in keep]
        if 'weights' in m: m['weights'] = [m['weights'][i] for i in keep if i < len(m['weights'])]
        if 'extras' in m and 'targetNames' in m['extras']: m['extras']['targetNames'] = [m['extras']['targetNames'][i] for i in keep if i < len(m['extras']['targetNames'])]
    # 2. used accessors -> renumber
    used = []
    def use(a):
        if a not in used: used.append(a)
    for m in j['meshes']:
        for p in m['primitives']:
            for a in p['attributes'].values(): use(a)
            if 'indices' in p: use(p['indices'])
            for t in p.get('targets', []):
                for a in t.values(): use(a)
    for s in j.get('skins', []):
        if 'inverseBindMatrices' in s: use(s['inverseBindMatrices'])
    for an in j.get('animations', []):
        for s in an['samplers']: use(s['input']); use(s['output'])
    amap = {o: n for n, o in enumerate(used)}
    for m in j['meshes']:
        for p in m['primitives']:
            p['attributes'] = {k: amap[v] for k, v in p['attributes'].items()}
            if 'indices' in p: p['indices'] = amap[p['indices']]
            p['targets'] = [{k: amap[v] for k, v in t.items()} for t in p.get('targets', [])] if 'targets' in p else None
            if p['targets'] is None: del p['targets']
    for s in j.get('skins', []):
        if 'inverseBindMatrices' in s: s['inverseBindMatrices'] = amap[s['inverseBindMatrices']]
    for an in j.get('animations', []):
        for s in an['samplers']: s['input'] = amap[s['input']]; s['output'] = amap[s['output']]
    old_acc = j['accessors']; j['accessors'] = [old_acc[a] for a in used]
    # 3. textures: downscale
    imgdata = {}
    for i, im in enumerate(j.get('images', [])):
        bv = j['bufferViews'][im['bufferView']]; raw = b[bv.get('byteOffset', 0):bv.get('byteOffset', 0) + bv['byteLength']]
        img = Image.open(io.BytesIO(raw))
        if max(img.size) > max_tex:
            img.thumbnail((max_tex, max_tex), Image.LANCZOS); out = io.BytesIO()
            if im.get('mimeType') == 'image/jpeg': img.convert('RGB').save(out, 'JPEG', quality=88)
            else: img.save(out, 'PNG', optimize=True)
            raw = out.getvalue()
        imgdata[im['bufferView']] = raw
    # 4. rebuild the binary chunk with only the used buffer views
    used_bv = []
    for a in j['accessors']:
        if a['bufferView'] not in used_bv: used_bv.append(a['bufferView'])
    for im in j.get('images', []):
        if im['bufferView'] not in used_bv: used_bv.append(im['bufferView'])
    bvmap = {o: n for n, o in enumerate(used_bv)}; nb = bytearray(); newbv = []
    for o in used_bv:
        bv = dict(j['bufferViews'][o]); nb += b'\0' * (-len(nb) % 4)
        data = imgdata.get(o, b[bv.get('byteOffset', 0):bv.get('byteOffset', 0) + bv['byteLength']])
        bv['byteOffset'] = len(nb); bv['byteLength'] = len(data); nb += data; newbv.append(bv)
    for a in j['accessors']: a['bufferView'] = bvmap[a['bufferView']]
    for im in j.get('images', []): im['bufferView'] = bvmap[im['bufferView']]
    j['bufferViews'] = newbv; j['buffers'] = [{'byteLength': len(nb)}]
    write_glb(dst, j, bytes(nb))
    print(f'morph targets kept: {len(keep)}; {len(b)/1e6:.1f} MB -> {len(nb)/1e6:.1f} MB')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], int(sys.argv[sys.argv.index('--max-tex') + 1]) if '--max-tex' in sys.argv else 1024)
