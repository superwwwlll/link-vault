"""Compose the four comparison images from the rendered phone screens."""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SRC = Path("/tmp/lv-design")
OUT = Path("/workspace/design/proposals/out")
FONT = "/tmp/fonts/NotoSansSC.ttf"


def fit(name, width):
    image = Image.open(SRC / f"{name}.png").convert("RGB")
    height = round(image.height * width / image.width)
    return image.resize((width, height), Image.Resampling.LANCZOS)


def sheet(title, subtitle, names, labels, path):
    title_font = ImageFont.truetype(FONT, 32)
    body = ImageFont.truetype(FONT, 18)
    width = 340
    phones = [fit(name, width) for name in names]
    phone_height = phones[0].height
    margin, gap, label_height, header = 36, 28, 36, 140
    canvas_width = margin * 2 + width * 2 + gap
    canvas_height = header + (phone_height + label_height) * 2 + gap + margin
    canvas = Image.new("RGB", (canvas_width, canvas_height), "#F4F3F0")
    draw = ImageDraw.Draw(canvas)
    draw.text((margin, 28), title, font=title_font, fill="#1C1C1E")
    draw.text((margin, 72), subtitle, font=body, fill="#3A3834")
    draw.text((margin, 96), "设计效果图，非已实现功能 / 非真机。示例数据，不是用户收藏。", font=body, fill="#3A3834")
    for index, (phone, label) in enumerate(zip(phones, labels)):
        column, row = index % 2, index // 2
        x = margin + column * (width + gap)
        y = header + row * (phone_height + label_height + gap)
        canvas.paste(phone, (x, y))
        draw.rectangle([x, y, x + width - 1, y + phone_height - 1], outline="#D9D5CE")
        draw.text((x, y + phone_height + 8), label, font=body, fill="#1C1C1E")
    canvas.save(path, "PNG")


def comparison(path):
    title_font = ImageFont.truetype(FONT, 32)
    body = ImageFont.truetype(FONT, 18)
    width = 300
    phones = [fit(name, width) for name in ("a-home", "b-home", "c-home")]
    phone_height = phones[0].height
    margin, gap, header = 36, 24, 140
    canvas = Image.new("RGB", (margin * 2 + width * 3 + gap * 2, header + phone_height + 48), "#F4F3F0")
    draw = ImageDraw.Draw(canvas)
    draw.text((margin, 28), "三套首页并排", font=title_font, fill="#1C1C1E")
    draw.text((margin, 72), "左工具，中书刊，右收藏库。同一组示例收藏。", font=body, fill="#3A3834")
    draw.text((margin, 96), "设计效果图，非已实现功能 / 非真机。", font=body, fill="#3A3834")
    for index, (phone, caption) in enumerate(zip(phones, ("A 工具", "B 书刊", "C 收藏库"))):
        x = margin + index * (width + gap)
        canvas.paste(phone, (x, header))
        draw.rectangle([x, header, x + width - 1, header + phone_height - 1], outline="#D9D5CE")
        draw.text((x, header + phone_height + 10), caption, font=body, fill="#1C1C1E")
    canvas.save(path, "PNG")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    labels = ["首页", "首页 · 深色", "阅读详情", "添加收藏"]
    sheet("A · 工具", "白底、无衬线、细分割线。选中项是黑色下划线。", ["a-home", "a-dark", "a-detail", "a-add"], labels, OUT / "A-tool-sheet.png")
    sheet("B · 书刊", "暖纸色、衬线标题、朱红下划线。首屏只放三篇。", ["b-home", "b-dark", "b-detail", "b-add"], labels, OUT / "B-reading-sheet.png")
    sheet("C · 收藏库", "冷灰底、编号目录、青绿下划线。第一条标题更大。", ["c-home", "c-dark", "c-detail", "c-add"], labels, OUT / "C-archive-sheet.png")
    comparison(OUT / "compare-homes.png")
