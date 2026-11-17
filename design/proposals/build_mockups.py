#!/usr/bin/env python3
"""Render three static Chinese mobile mockups. Not the app, not a device."""

from pathlib import Path

ROOT = Path("/workspace/design/proposals")
SCREENS = ROOT / "screens"
FONT_SANS = "file:///tmp/fonts/NotoSansSC.ttf"
FONT_SERIF = "file:///tmp/fonts/NotoSerifSC.ttf"

ITEMS = [
    {
        "title": "把读过的长文留在自己的书架上",
        "source": "少数派",
        "date": "10月3日",
        "tag": "阅读",
        "note": "先看结构，再决定要不要翻译。",
        "url": "https://sspai.com/post/reading-shelf",
    },
    {
        "title": "如何给十年后的自己留一份还能检索的阅读记录",
        "source": "知乎",
        "date": "8月18日",
        "tag": "方法",
        "note": "检索比再收藏一次更重要。",
        "url": "https://www.zhihu.com/question/reading-log",
    },
    {
        "title": "城市里还在营业的独立书店，靠什么撑过这一年",
        "source": "36氪",
        "date": "6月2日",
        "tag": "城市",
        "note": "已读。想留下来的是书店怎么活。",
        "url": "https://36kr.com/p/bookstores",
    },
    {
        "title": "一次连接里还剩下什么成本",
        "source": "GitHub",
        "date": "12月11日",
        "tag": "网络",
        "note": "协议本身不贵，来回确认才贵。",
        "url": "https://github.com/http/connection-cost",
    },
    {
        "title": "活版印刷的条目比想象中更好读",
        "source": "维基百科",
        "date": "4月9日",
        "tag": "历史",
        "note": "从字模写到工坊，比词条标题长得多。",
        "url": "https://zh.wikipedia.org/wiki/Printing",
    },
]


def icon(name, color):
    stroke = f'stroke="{color}" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round"'
    paths = {
        "search": f'<circle cx="11" cy="11" r="6.2" {stroke}/><path d="M15.6 15.6L19 19" {stroke}/>',
        "plus": f'<path d="M12 5.5v13M5.5 12h13" {stroke}/>',
        "back": f'<path d="M14.5 5.5L8 12l6.5 6.5" {stroke}/>',
        "bookmark": f'<path d="M7 4.8h10v14.4l-5-3.1-5 3.1V4.8z" {stroke}/>',
        "note": f'<path d="M14.2 5.2l4.2 4.2L9.2 18.6H5v-4.2L14.2 5.2z" {stroke}/>',
        "tag": f'<path d="M4.8 12.2L12 5h6.2v6.2l-7.2 7.2-6.2-6.2z" {stroke}/><circle cx="15.2" cy="8.6" r="0.9" fill="{color}" stroke="none"/>',
        "gear": f'<path d="M5 8h14M5 12h14M5 16h14" {stroke}/><circle cx="9" cy="8" r="1.7" fill="{color}" stroke="none"/><circle cx="15" cy="12" r="1.7" fill="{color}" stroke="none"/><circle cx="11" cy="16" r="1.7" fill="{color}" stroke="none"/>',
        "open": f'<path d="M8 16l8-8M11 8h5v5" {stroke}/>',
    }
    return f'<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">{paths[name]}</svg>'


def tabs(active, ink, muted, line):
    labels = ["未归档", "未读", "已读", "归档"]
    parts = []
    for label in labels:
        on = label == active
        color = ink if on else muted
        weight = 600 if on else 430
        rule = f'<i style="background:{line}"></i>' if on else "<i></i>"
        parts.append(f'<button style="color:{color};font-weight:{weight}">{label}{rule}</button>')
    return "".join(parts)


def tabbar(active, ink, muted):
    items = [("bookmark", "收藏"), ("note", "笔记"), ("tag", "标签"), ("gear", "设置")]
    bits = []
    for name, label in items:
        color = ink if label == active else muted
        weight = 600 if label == active else 430
        bits.append(
            f'<div class="tab"><span style="color:{color}">{icon(name, color)}</span>'
            f'<em style="color:{color};font-weight:{weight}">{label}</em></div>'
        )
    return "".join(bits)


def status(ink):
    return f'''<div class="status" style="color:{ink}">
      <span>08:30</span>
      <span class="sig"><i></i><i></i><i></i><b></b></span>
    </div>'''


CSS = """
@font-face { font-family: "Sans"; src: url("__SANS__") format("truetype"); font-weight: 100 900; font-display: block; }
@font-face { font-family: "Serif"; src: url("__SERIF__") format("truetype"); font-weight: 100 900; font-display: block; }
* { box-sizing: border-box; margin: 0; padding: 0; }
html, body { width: 390px; height: 844px; overflow: hidden; background: transparent; }
body { font-family: "Sans", sans-serif; -webkit-font-smoothing: antialiased; }
button { background: none; border: 0; font: inherit; color: inherit; }
.phone { width: 390px; height: 844px; position: relative; overflow: hidden; }
.status { height: 54px; padding: 18px 22px 0; display: flex; justify-content: space-between; align-items: flex-end; font-size: 15px; font-weight: 600; letter-spacing: 0.2px; }
.sig { display: flex; gap: 3px; align-items: flex-end; height: 12px; }
.sig i, .sig b { display: block; width: 3px; border-radius: 1px; background: currentColor; }
.sig i:nth-child(1) { height: 5px; opacity: .45; }
.sig i:nth-child(2) { height: 8px; opacity: .7; }
.sig i:nth-child(3) { height: 11px; }
.sig b { width: 16px; height: 8px; border-radius: 2px; opacity: .9; margin-left: 4px; }
.nav { height: 48px; display: flex; align-items: center; padding: 0 8px 0 20px; }
.nav h1 { font-size: 17px; font-weight: 600; flex: 1; }
.nav .icons { display: flex; }
.nav .icons span, .nav .back { width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; }
.nav .action { font-size: 17px; font-weight: 600; padding: 0 14px; height: 44px; }
.scopes { display: flex; gap: 22px; padding: 2px 20px 0; height: 44px; align-items: flex-end; }
.scopes button { font-size: 16px; line-height: 22px; padding-bottom: 8px; position: relative; }
.scopes i { position: absolute; left: 0; right: 0; bottom: 0; height: 2px; border-radius: 1px; }
.dock { position: absolute; left: 0; right: 0; bottom: 0; height: 78px; display: flex; padding: 8px 8px 22px; }
.dock .tab { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 3px; }
.dock em { font-style: normal; font-size: 10px; line-height: 12px; letter-spacing: 0.2px; }
.list { padding: 6px 0 90px; }
.row { padding: 13px 20px 14px; }
.row h2 { font-size: 17px; line-height: 24px; font-weight: 500; }
.row p { margin-top: 3px; font-size: 13px; line-height: 18px; }
.count { padding: 10px 20px 2px; font-size: 13px; line-height: 18px; }
"""

CSS = CSS.replace("__SANS__", FONT_SANS).replace("__SERIF__", FONT_SERIF)


def page(body, extra=""):
    return f"<!doctype html><html><head><meta charset='utf-8'><style>{CSS}{extra}</style></head><body>{body}</body></html>"


def home_a(dark=False):
    bg, ink, sub, line = ("#111111", "#F5F5F7", "#8E8E93", "#F5F5F7") if dark else ("#FFFFFF", "#1C1C1E", "#8E8E93", "#1C1C1E")
    hair = "#2C2C2E" if dark else "#ECECEF"
    rows = []
    for item in ITEMS:
        rows.append(
            f'<div class="row" style="border-bottom:0.5px solid {hair}">'
            f'<h2 style="color:{ink}">{item["title"]}</h2>'
            f'<p style="color:{sub}">{item["source"]} · {item["date"]} · {item["tag"]}</p></div>'
        )
    body = f'''<div class="phone" style="background:{bg}">
      {status(ink)}
      <div class="nav"><h1 style="color:{ink}">收藏</h1>
        <div class="icons"><span>{icon("search", ink)}</span><span>{icon("plus", ink)}</span></div></div>
      <div class="scopes">{tabs("未归档", ink, sub, line)}</div>
      <div class="count" style="color:{sub}">未归档 · 5 条</div>
      <div class="list">{''.join(rows)}</div>
      <div class="dock" style="background:{bg};border-top:0.5px solid {hair}">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body)


def home_b(dark=False):
    bg, ink, sub, accent = ("#221C16", "#F6F0E6", "#C4B6A4", "#E07A64") if dark else ("#F4EFE6", "#2A241C", "#8A7B68", "#9C3B2E")
    hair = "#3A3128" if dark else "#E3D8C8"
    rows = []
    for index, item in enumerate(ITEMS[:3]):
        excerpt = f'<p class="ex" style="color:{sub}">{item["note"]}</p>' if index == 0 else ""
        rows.append(
            f'<article><div class="meta" style="color:{accent}">{item["source"]}</div>'
            f'<h2 style="color:{ink}">{item["title"]}</h2>{excerpt}'
            f'<p class="when" style="color:{sub}">{item["date"]} · {item["tag"]}</p></article>'
        )
    extra = f'''
      .mast {{ font-family: "Serif", serif; font-size: 34px; line-height: 42px; font-weight: 560; padding: 8px 28px 0; }}
      .kicker {{ font-size: 12px; letter-spacing: 0.28em; padding: 6px 28px 0; }}
      .scopes {{ padding-left: 28px; padding-right: 28px; gap: 26px; }}
      .scopes button {{ font-family: "Serif", serif; font-size: 17px; }}
      article {{ padding: 18px 28px 16px; border-top: 0.5px solid {hair}; }}
      article h2 {{ font-family: "Serif", serif; font-size: 22px; line-height: 32px; font-weight: 560; }}
      .meta {{ font-size: 12px; letter-spacing: 0.14em; margin-bottom: 6px; }}
      .ex {{ font-family: "Serif", serif; font-size: 15px; line-height: 24px; margin-top: 8px; }}
      .when {{ font-size: 13px; margin-top: 8px; }}
    '''
    body = f'''<div class="phone" style="background:{bg}">
      {status(ink)}
      <div class="kicker" style="color:{sub}">链藏</div>
      <div class="mast" style="color:{ink}">书架</div>
      <div class="nav" style="height:40px"><div class="icons" style="margin-left:auto"><span>{icon("search", ink)}</span><span>{icon("plus", ink)}</span></div></div>
      <div class="scopes">{tabs("未归档", ink, sub, accent)}</div>
      <div style="height:18px"></div>
      {''.join(rows)}
      <div class="dock" style="background:{bg}">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body, extra)


def home_c(dark=False):
    bg, ink, sub, accent = ("#101214", "#F3F5F7", "#9AA3AD", "#7ED0C6") if dark else ("#F3F4F6", "#15181D", "#6E7682", "#0F6E66")
    lead = ITEMS[0]
    rest = []
    for number, item in enumerate(ITEMS[1:], start=2):
        rest.append(
            f'<div class="entry"><b style="color:{sub}">{number:02d}</b>'
            f'<div><h2 style="color:{ink}">{item["title"]}</h2>'
            f'<p style="color:{sub}">{item["source"]} · {item["date"]}</p></div></div>'
        )
    extra = '''
      .brand { font-size: 13px; letter-spacing: 0.22em; padding: 2px 24px 0; font-weight: 500; }
      .mast { font-size: 28px; line-height: 34px; font-weight: 560; padding: 2px 24px 0; }
      .scopes { padding-left: 24px; }
      .lead { padding: 22px 24px 8px; }
      .lead .no { font-size: 12px; letter-spacing: 0.16em; }
      .lead h2 { font-size: 26px; line-height: 36px; font-weight: 560; margin-top: 8px; }
      .lead p { margin-top: 10px; font-size: 14px; line-height: 22px; }
      .entry { display: flex; gap: 14px; padding: 16px 24px; }
      .entry b { font-weight: 500; font-size: 12px; letter-spacing: 0.08em; padding-top: 4px; }
      .entry h2 { font-size: 16px; line-height: 23px; font-weight: 500; }
      .entry p { margin-top: 3px; font-size: 13px; }
    '''
    body = f'''<div class="phone" style="background:{bg}">
      {status(ink)}
      <div class="brand" style="color:{sub}">链藏</div>
      <div class="mast" style="color:{ink}">收藏库</div>
      <div class="nav" style="height:36px"><div class="icons" style="margin-left:auto"><span>{icon("search", ink)}</span><span>{icon("plus", ink)}</span></div></div>
      <div class="scopes">{tabs("未归档", ink, sub, accent)}</div>
      <div class="lead">
        <div class="no" style="color:{accent}">01 · {lead["source"]}</div>
        <h2 style="color:{ink}">把读过的长文留在<br>自己的书架上</h2>
        <p style="color:{sub}">{lead["date"]} · {lead["tag"]}</p>
      </div>
      {''.join(rest)}
      <div class="dock" style="background:{bg}">{tabbar("收藏", accent if not dark else accent, sub)}</div>
    </div>'''
    return page(body, extra)


def detail_a():
    ink, sub, hair = "#1C1C1E", "#8E8E93", "#ECECEF"
    item = ITEMS[0]
    extra = '''
      .body { padding: 8px 20px 100px; }
      .body h2 { font-size: 22px; line-height: 30px; font-weight: 600; }
      .meta { margin-top: 8px; font-size: 13px; }
      .link { margin-top: 18px; font-size: 15px; line-height: 22px; word-break: break-all; }
      .open { margin-top: 8px; font-size: 17px; font-weight: 600; }
      .label { margin-top: 26px; font-size: 13px; }
      .copy { margin-top: 6px; font-size: 17px; line-height: 26px; }
    '''
    body = f'''<div class="phone" style="background:#fff">
      {status(ink)}
      <div class="nav"><span class="back">{icon("back", ink)}</span><h1 style="color:{ink}">详情</h1><span class="action" style="color:{ink}">更多</span></div>
      <div class="body">
        <h2 style="color:{ink}">{item["title"]}</h2>
        <div class="meta" style="color:{sub}">{item["source"]} · {item["date"]} · {item["tag"]}</div>
        <div class="link" style="color:{ink}">{item["url"]}</div>
        <div class="open" style="color:{ink}">打开</div>
        <div class="label" style="color:{sub}">备注</div>
        <div class="copy" style="color:{ink}">{item["note"]}</div>
        <div class="label" style="color:{sub}">摘录</div>
        <div class="copy" style="color:{ink};border-top:0.5px solid {hair};padding-top:12px">长文不必堆在收藏夹里。留下标题、来源和一句自己的话，以后找得到，也还愿意读。</div>
      </div>
      <div class="dock" style="background:#fff;border-top:0.5px solid {hair}">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body, extra)


def detail_b():
    ink, sub, accent, hair = "#2A241C", "#8A7B68", "#9C3B2E", "#E3D8C8"
    item = ITEMS[0]
    extra = '''
      .body { padding: 12px 28px 100px; }
      .body h2 { font-family: "Serif", serif; font-size: 32px; line-height: 44px; font-weight: 560; }
      .by { margin-top: 14px; font-size: 13px; letter-spacing: 0.08em; }
      .rule { height: 0.5px; margin: 22px 0; }
      .prose { font-family: "Serif", serif; font-size: 17px; line-height: 32px; }
      .mine { margin-top: 28px; font-size: 12px; letter-spacing: 0.18em; }
      .quote { font-family: "Serif", serif; font-size: 20px; line-height: 32px; margin-top: 8px; }
    '''
    body = f'''<div class="phone" style="background:#F4EFE6">
      {status(ink)}
      <div class="nav"><span class="back">{icon("back", ink)}</span><h1></h1><span class="action" style="color:{accent};font-family:Serif,serif">打开</span></div>
      <div class="body">
        <h2 style="color:{ink}">把读过的长文留在<br>自己的书架上</h2>
        <div class="by" style="color:{sub}">{item["source"]} · {item["date"]}</div>
        <div class="rule" style="background:{hair}"></div>
        <div class="prose" style="color:{ink}">长文不必堆在收藏夹里。留下标题、来源和一句自己的话，以后找得到，也还愿意读。</div>
        <div class="mine" style="color:{accent}">我的一句</div>
        <div class="quote" style="color:{ink}">{item["note"]}</div>
        <div class="by" style="color:{sub};margin-top:22px">{item["tag"]} · {item["url"].replace("https://","")}</div>
      </div>
      <div class="dock" style="background:#F4EFE6">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body, extra)


def detail_c():
    ink, sub, accent = "#15181D", "#6E7682", "#0F6E66"
    item = ITEMS[0]
    rows = "".join(
        f'<div class="spec"><span style="color:{sub}">{k}</span><strong style="color:{ink}">{v}</strong></div>'
        for k, v in [("来源", item["source"]), ("日期", item["date"]), ("标签", item["tag"]), ("打开", "原链接")]
    )
    extra = '''
      .body { padding: 6px 24px 100px; }
      .eyebrow { font-size: 12px; letter-spacing: 0.18em; }
      .body h2 { font-size: 26px; line-height: 36px; font-weight: 560; margin-top: 8px; }
      .spec { display: flex; justify-content: space-between; gap: 16px; padding: 13px 0; font-size: 16px; }
      .spec strong { font-weight: 500; }
      .block { margin-top: 18px; font-size: 13px; }
      .copy { margin-top: 6px; font-size: 16px; line-height: 26px; }
    '''
    body = f'''<div class="phone" style="background:#F3F4F6">
      {status(ink)}
      <div class="nav"><span class="back">{icon("back", ink)}</span><h1 style="color:{ink};font-size:17px">条目</h1><span class="action" style="color:{accent}">更多</span></div>
      <div class="body">
        <div class="eyebrow" style="color:{accent}">01</div>
        <h2 style="color:{ink}">把读过的长文留在<br>自己的书架上</h2>
        <div style="margin-top:16px">{rows}</div>
        <div class="block" style="color:{sub}">备注</div>
        <div class="copy" style="color:{ink}">{item["note"]}</div>
        <div class="block" style="color:{sub}">摘录</div>
        <div class="copy" style="color:{ink}">长文不必堆在收藏夹里。留下标题、来源和一句自己的话，以后找得到，也还愿意读。</div>
      </div>
      <div class="dock" style="background:#F3F4F6">{tabbar("收藏", accent, sub)}</div>
    </div>'''
    return page(body, extra)


def add_a():
    ink, sub, hair = "#1C1C1E", "#8E8E93", "#E4E4E7"
    item = ITEMS[0]
    fields = [("链接", item["url"]), ("标题", item["title"]), ("备注", item["note"]), ("标签", item["tag"])]
    blocks = "".join(
        f'<label><span style="color:{sub}">{name}</span><div style="color:{ink};border-bottom:0.5px solid {hair}">{value}</div></label>'
        for name, value in fields
    )
    extra = '''
      label { display: block; padding: 16px 20px 0; }
      label span { display: block; font-size: 13px; line-height: 18px; }
      label div { font-size: 17px; line-height: 24px; padding: 6px 0 10px; word-break: break-all; }
    '''
    body = f'''<div class="phone" style="background:#fff">
      {status(ink)}
      <div class="nav"><span class="action" style="color:{sub};font-weight:450;padding-left:12px">取消</span><h1 style="text-align:center;color:{ink}">新建收藏</h1><span class="action" style="color:{ink}">保存</span></div>
      {blocks}
      <div class="dock" style="background:#fff;border-top:0.5px solid {hair}">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body, extra)


def add_b():
    ink, sub, accent, hair = "#2A241C", "#8A7B68", "#9C3B2E", "#E3D8C8"
    item = ITEMS[0]
    fields = [("链接", item["url"]), ("标题", item["title"]), ("一句", item["note"]), ("标签", item["tag"])]
    blocks = "".join(
        f'<label><span style="color:{sub}">{name}</span><div style="color:{ink}">{value}</div><u style="background:{hair}"></u></label>'
        for name, value in fields
    )
    extra = '''
      .ask { font-family: "Serif", serif; font-size: 28px; line-height: 38px; padding: 8px 28px 12px; }
      label { display: block; padding: 12px 28px 0; }
      label span { font-family: "Serif", serif; font-size: 13px; letter-spacing: 0.12em; }
      label div { font-size: 17px; line-height: 26px; padding: 6px 0 8px; word-break: break-all; }
      label u { display: block; height: 0.5px; text-decoration: none; }
    '''
    body = f'''<div class="phone" style="background:#F4EFE6">
      {status(ink)}
      <div class="nav"><span class="back">{icon("back", ink)}</span><h1></h1><span class="action" style="color:{accent};font-family:Serif,serif">收好</span></div>
      <div class="ask" style="color:{ink}">把这一篇留在书架上</div>
      {blocks}
      <div class="dock" style="background:#F4EFE6">{tabbar("收藏", ink, sub)}</div>
    </div>'''
    return page(body, extra)


def add_c():
    ink, sub, accent = "#15181D", "#6E7682", "#0F6E66"
    item = ITEMS[0]
    fields = [("链接", item["url"]), ("标题", item["title"]), ("备注", item["note"]), ("标签", item["tag"])]
    blocks = "".join(
        f'<label><span style="color:{sub}">{name}</span><div style="color:{ink};background:#E7E9ED">{value}</div></label>'
        for name, value in fields
    )
    extra = '''
      .ask { font-size: 13px; letter-spacing: 0.18em; padding: 4px 24px 0; }
      h2.ask2 { font-size: 26px; line-height: 34px; font-weight: 560; padding: 4px 24px 12px; }
      label { display: block; padding: 8px 24px 0; }
      label span { display: block; font-size: 12px; letter-spacing: 0.08em; margin-bottom: 6px; }
      label div { font-size: 16px; line-height: 23px; padding: 12px 14px; border-radius: 10px; word-break: break-all; }
    '''
    body = f'''<div class="phone" style="background:#F3F4F6">
      {status(ink)}
      <div class="nav"><span class="back">{icon("back", ink)}</span><h1 style="color:{ink}">新条目</h1><span class="action" style="color:{accent}">保存</span></div>
      <div class="ask" style="color:{accent}">著录</div>
      <h2 class="ask2" style="color:{ink}">收入收藏库</h2>
      {blocks}
      <div class="dock" style="background:#F3F4F6">{tabbar("收藏", accent, sub)}</div>
    </div>'''
    return page(body, extra)


PAGES = {
    "a-home": home_a,
    "a-dark": lambda: home_a(True),
    "a-detail": detail_a,
    "a-add": add_a,
    "b-home": home_b,
    "b-dark": lambda: home_b(True),
    "b-detail": detail_b,
    "b-add": add_b,
    "c-home": home_c,
    "c-dark": lambda: home_c(True),
    "c-detail": detail_c,
    "c-add": add_c,
}


def main():
    SCREENS.mkdir(parents=True, exist_ok=True)
    for name, builder in PAGES.items():
        (SCREENS / f"{name}.html").write_text(builder(), encoding="utf-8")
        print(name)


if __name__ == "__main__":
    main()
