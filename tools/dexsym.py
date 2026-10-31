#!/usr/bin/env python3
"""极简 dex 符号工具（纯标准库，无 dexdump/jadx 依赖）。

用法:
  dexsym.py <apk|dex> classes [关键字]        # 列 class_def 描述符
  dexsym.py <apk|dex> methods <类名>          # 列该类的方法（名 + 原型）与字段数
  dexsym.py <apk|dex> has <类名>[#方法名]     # 存在性检查，打印 OK / MISSING
  dexsym.py <apk|dex> proto <类型描述符子串>  # 全包搜「原型里出现该类型」的方法（签名锚点候选）

类名点号/斜杠均可：xa.g  xa/g  Lxa/g;
"""
import sys, zipfile, struct

def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not x & 0x80:
            return r, o
        s += 7

class Dex:
    def __init__(self, b):
        self.b = b
        (self.sid_n, self.sid_off) = struct.unpack_from('<II', b, 0x38)
        (self.tid_n, self.tid_off) = struct.unpack_from('<II', b, 0x40)
        (self.pid_n, self.pid_off) = struct.unpack_from('<II', b, 0x48)
        (self.fid_n, self.fid_off) = struct.unpack_from('<II', b, 0x50)
        (self.mid_n, self.mid_off) = struct.unpack_from('<II', b, 0x58)
        (self.cd_n, self.cd_off) = struct.unpack_from('<II', b, 0x60)

    def string(self, i):
        off = struct.unpack_from('<I', self.b, self.sid_off + 4 * i)[0]
        n, o = uleb(self.b, off)
        return self.b[o:o + n].decode('utf-8', 'replace')

    def type_name(self, i):
        return self.string(struct.unpack_from('<I', self.b, self.tid_off + 4 * i)[0])

    def type_list(self, off):
        if not off:
            return []
        n = struct.unpack_from('<I', self.b, off)[0]
        return [self.type_name(struct.unpack_from('<H', self.b, off + 4 + 2 * i)[0]) for i in range(n)]

    def proto_sig(self, i):
        ret, params_off = struct.unpack_from('<II', self.b, self.pid_off + 12 * i + 4)
        return "(" + "".join(self.type_list(params_off)) + ")" + self.type_name(ret)

    def method(self, i):
        cls, proto, name = struct.unpack_from('<HHI', self.b, self.mid_off + 8 * i)
        return self.type_name(cls), self.string(name), self.proto_sig(proto)

    def class_defs(self):
        for i in range(self.cd_n):
            base = self.cd_off + 32 * i
            cidx = struct.unpack_from('<I', self.b, base)[0]
            data_off = struct.unpack_from('<I', self.b, base + 24)[0]
            yield self.type_name(cidx), data_off

    def members(self, data_off):
        """返回 (methods, field_count)；methods 元素为 (name, sig)。"""
        if not data_off:
            return [], 0
        o = data_off
        sf, o = uleb(self.b, o)
        inf, o = uleb(self.b, o)
        dm, o = uleb(self.b, o)
        vm, o = uleb(self.b, o)
        for _ in range(sf + inf):
            _, o = uleb(self.b, o); _, o = uleb(self.b, o)
        out = []
        for cnt in (dm, vm):
            idx = 0
            for _ in range(cnt):
                d, o = uleb(self.b, o); idx += d
                _, o = uleb(self.b, o)
                _, o = uleb(self.b, o)
                _, name, sig = self.method(idx)
                out.append((name, sig))
        return out, sf + inf

def norm(n):
    n = n.replace('.', '/').lstrip('L').rstrip(';')
    return n

def blobs(path):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            return [z.read(n) for n in sorted(x for x in z.namelist() if x.startswith('classes') and x.endswith('.dex'))]
    return [open(path, 'rb').read()]

def main():
    path, cmd = sys.argv[1], sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else None
    dexes = [Dex(b) for b in blobs(path)]

    if cmd == 'classes':
        allc = [c for d in dexes for c, _ in d.class_defs()]
        hit = [c for c in allc if not arg or arg in c]
        print(len(hit))
        for c in sorted(hit):
            print(c)
        return

    if cmd == 'methods':
        want = norm(arg)
        for d in dexes:
            for c, off in d.class_defs():
                if norm(c) == want:
                    ms, fc = d.members(off)
                    print("%s  fields=%d methods=%d" % (c, fc, len(ms)))
                    for name, sig in sorted(ms):
                        print("  %s%s" % (name, sig))
                    return
        print("MISSING class " + want)
        return

    if cmd == 'has':
        want, _, mname = (arg or "").partition('#')
        want = norm(want)
        for d in dexes:
            for c, off in d.class_defs():
                if norm(c) == want:
                    if not mname:
                        print("OK class " + c)
                        return
                    ms, _ = d.members(off)
                    hits = [s for n, s in ms if n == mname]
                    if hits:
                        print("OK %s#%s" % (c, mname))
                        for s in hits:
                            print("   " + s)
                        return
                    print("MISSING method %s#%s (class exists)" % (c, mname))
                    return
        print("MISSING class " + want)
        return

    if cmd == 'proto':
        seen = set()
        for d in dexes:
            for i in range(d.mid_n):
                c, name, sig = d.method(i)
                if arg in sig:
                    key = (c, name, sig)
                    if key not in seen:
                        seen.add(key)
                        print("%s#%s%s" % (c, name, sig))
        print("count=%d" % len(seen))
        return

    print("unknown cmd")

main()
