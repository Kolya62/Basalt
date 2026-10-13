"""Generates the Basalt logo/icon and README banners as SVG (rendered to PNG separately)."""
import sys

OUT = sys.argv[1]

def column(cx, ty, r, by, uid, rim=False):
    d = 0.433 * r
    top = f"{cx-r},{ty} {cx-r/2},{ty-d} {cx+r/2},{ty-d} {cx+r},{ty} {cx+r/2},{ty+d} {cx-r/2},{ty+d}"
    left = f"{cx-r},{ty} {cx-r/2},{ty+d} {cx-r/2},{by+d} {cx-r},{by}"
    front = f"{cx-r/2},{ty+d} {cx+r/2},{ty+d} {cx+r/2},{by+d} {cx-r/2},{by+d}"
    right = f"{cx+r/2},{ty+d} {cx+r},{ty} {cx+r},{by} {cx+r/2},{by+d}"
    s = (f'<polygon points="{left}" fill="url(#left{uid})"/>'
         f'<polygon points="{front}" fill="url(#front{uid})"/>'
         f'<polygon points="{right}" fill="url(#right{uid})"/>'
         f'<polygon points="{top}" fill="url(#top{uid})"/>')
    if rim:
        s += (f'<polyline points="{cx-r},{ty} {cx-r/2},{ty+d} {cx+r/2},{ty+d} {cx+r},{ty}" fill="none" '
              f'stroke="#ffb27a" stroke-opacity="0.85" stroke-width="{r*0.06:.2f}" stroke-linejoin="round"/>')
    return s

def defs(uid, y0, y1):
    """Face gradients; faces warm up towards the lava at the bottom (y0..y1 in user space)."""
    return f'''
    <linearGradient id="top{uid}" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#9aa3b2"/><stop offset="1" stop-color="#5f6776"/>
    </linearGradient>
    <linearGradient id="left{uid}" gradientUnits="userSpaceOnUse" x1="0" y1="{y0}" x2="0" y2="{y1}">
      <stop offset="0" stop-color="#454c58"/><stop offset="0.7" stop-color="#363b45"/><stop offset="1" stop-color="#6b3420"/>
    </linearGradient>
    <linearGradient id="front{uid}" gradientUnits="userSpaceOnUse" x1="0" y1="{y0}" x2="0" y2="{y1}">
      <stop offset="0" stop-color="#343a44"/><stop offset="0.65" stop-color="#2a2e36"/><stop offset="1" stop-color="#7a3519"/>
    </linearGradient>
    <linearGradient id="right{uid}" gradientUnits="userSpaceOnUse" x1="0" y1="{y0}" x2="0" y2="{y1}">
      <stop offset="0" stop-color="#23272e"/><stop offset="0.7" stop-color="#1c1f25"/><stop offset="1" stop-color="#5a2412"/>
    </linearGradient>'''

def logo_group(uid):
    """Three basalt columns over a lava glow, in a 128x128 box."""
    return (column(38, 54, 18, 112, uid) + column(90, 64, 18, 112, uid) + column(64, 34, 23, 112, uid, rim=True))

def icon():
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">
  <defs>{defs("i", 20, 120)}
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#1b1f26"/><stop offset="1" stop-color="#0b0d10"/>
    </linearGradient>
    <radialGradient id="lava" cx="0.5" cy="1.02" r="0.6">
      <stop offset="0" stop-color="#ffb347" stop-opacity="0.95"/><stop offset="0.35" stop-color="#ff5a1f" stop-opacity="0.75"/>
      <stop offset="1" stop-color="#ff5a1f" stop-opacity="0"/>
    </radialGradient>
    <clipPath id="clip"><rect width="128" height="128" rx="26"/></clipPath>
  </defs>
  <g clip-path="url(#clip)">
    <rect width="128" height="128" fill="url(#bg)"/>
    <rect y="40" width="128" height="88" fill="url(#lava)"/>
    {logo_group("i")}
    <rect y="112" width="128" height="16" fill="url(#lava)" opacity="0.9"/>
  </g>
</svg>'''

def hex_pattern(x0, y0, x1, y1, r, opacity):
    out = []
    w = 1.5 * r
    h = 0.866 * r * 2
    col = 0
    x = x0
    while x < x1 + r:
        y = y0 + (h / 2 if col % 2 else 0)
        while y < y1 + h:
            pts = " ".join(f"{x + r * c:.1f},{y + r * s:.1f}" for c, s in
                           [(1, 0), (0.5, 0.866), (-0.5, 0.866), (-1, 0), (-0.5, -0.866), (0.5, -0.866)])
            out.append(f'<polygon points="{pts}"/>')
            y += h
        x += w
        col += 1
    return f'<g fill="none" stroke="#ffffff" stroke-opacity="{opacity}" stroke-width="1.2">{"".join(out)}</g>'

def chip(x, y, text, accent=False):
    w = 22 + len(text) * 13.2
    stroke = "#ff7a3d" if accent else "#3a414d"
    fill = "#ff7a3d" if accent else "#1d2129"
    fo = "0.14" if accent else "0.9"
    color = "#ffb27a" if accent else "#c9d1d9"
    return (f'<rect x="{x}" y="{y}" width="{w:.0f}" height="44" rx="22" fill="{fill}" fill-opacity="{fo}" stroke="{stroke}" stroke-width="1.5"/>'
            f'<text x="{x + w / 2:.0f}" y="{y + 29}" text-anchor="middle" font-size="20" font-weight="600" fill="{color}">{text}</text>'), w

def banner(tagline, sub, chips):
    W, H = 1280, 400
    chip_svg = []
    x = 440
    for i, t in enumerate(chips):
        s, w = chip(x, 286, t, accent=(i == 0))
        chip_svg.append(s)
        x += w + 14
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Inter, 'Segoe UI', Helvetica, Arial, sans-serif">
  <defs>{defs("b", 20, 120)}
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#171a20"/><stop offset="1" stop-color="#0b0d10"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.18" cy="1.05" r="0.55">
      <stop offset="0" stop-color="#ff6a2b" stop-opacity="0.55"/><stop offset="0.45" stop-color="#ff5a1f" stop-opacity="0.18"/>
      <stop offset="1" stop-color="#ff5a1f" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="fade" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset="1" stop-color="#fff" stop-opacity="1"/>
    </linearGradient>
    <mask id="hexmask"><rect width="{W}" height="{H}" fill="url(#fade)"/></mask>
    <linearGradient id="line" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#ff4d1a"/><stop offset="0.5" stop-color="#ff9a3d"/><stop offset="1" stop-color="#ff4d1a" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="lava" cx="0.5" cy="1.02" r="0.6">
      <stop offset="0" stop-color="#ffb347" stop-opacity="0.95"/><stop offset="0.35" stop-color="#ff5a1f" stop-opacity="0.75"/>
      <stop offset="1" stop-color="#ff5a1f" stop-opacity="0"/>
    </radialGradient>
    <clipPath id="clip"><rect width="{W}" height="{H}" rx="28"/></clipPath>
    <clipPath id="logoclip"><rect width="128" height="128" rx="26"/></clipPath>
  </defs>
  <g clip-path="url(#clip)">
    <rect width="{W}" height="{H}" fill="url(#bg)"/>
    <g mask="url(#hexmask)">{hex_pattern(560, -20, W, H, 34, 0.07)}</g>
    <rect width="{W}" height="{H}" fill="url(#glow)"/>
    <g transform="translate(96 72) scale(2)">
      <g clip-path="url(#logoclip)">
        <rect width="128" height="128" fill="#12151a"/>
        <rect y="40" width="128" height="88" fill="url(#lava)"/>
        {logo_group("b")}
        <rect y="112" width="128" height="16" fill="url(#lava)" opacity="0.9"/>
      </g>
      <rect width="128" height="128" rx="26" fill="none" stroke="#ffffff" stroke-opacity="0.08" stroke-width="0.75"/>
    </g>
    <text x="436" y="168" font-size="104" font-weight="800" fill="#f5f7fa" letter-spacing="-3">Basalt</text>
    <text x="440" y="216" font-size="30" font-weight="600" fill="#e6e9ee">{tagline}</text>
    <text x="440" y="254" font-size="22" font-weight="400" fill="#8b949e">{sub}</text>
    {"".join(chip_svg)}
    <rect x="0" y="{H - 5}" width="{W}" height="5" fill="url(#line)"/>
  </g>
</svg>'''

files = {
    "icon.svg": icon(),
    "banner-en.svg": banner("All-in-one performance mod for Minecraft 26.3",
                            "Rendering · World generation · Server ticks · Memory",
                            ["Vulkan", "Fabric", "Java 25", "LGPL-3.0"]),
    "banner-ru.svg": banner("Мод на производительность для Minecraft 26.3",
                            "Рендер · Генерация мира · Тики сервера · Память",
                            ["Vulkan", "Fabric", "Java 25", "LGPL-3.0"]),
}
for name, svg in files.items():
    with open(f"{OUT}/{name}", "w", encoding="utf-8") as f:
        f.write(svg)
print("ok", list(files))
