from reportlab.lib.pagesizes import letter
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table,
                                TableStyle, ListFlowable, ListItem, KeepTogether)

FD = "/usr/share/fonts/truetype/liberation/"
pdfmetrics.registerFont(TTFont("Sans", FD + "LiberationSans-Regular.ttf"))
pdfmetrics.registerFont(TTFont("Sans-Bold", FD + "LiberationSans-Bold.ttf"))
pdfmetrics.registerFont(TTFont("Mono", FD + "LiberationMono-Regular.ttf"))
pdfmetrics.registerFont(TTFont("Mono-Bold", FD + "LiberationMono-Bold.ttf"))
pdfmetrics.registerFontFamily("Sans", normal="Sans", bold="Sans-Bold")
pdfmetrics.registerFontFamily("Mono", normal="Mono", bold="Mono-Bold")

OUT = "Pixel_Model_Check.pdf"

body = ParagraphStyle("body", fontName="Sans", fontSize=10, leading=14.5, spaceAfter=6)
small = ParagraphStyle("small", parent=body, fontSize=9.5, leading=13, spaceAfter=0)
title = ParagraphStyle("title", fontName="Sans-Bold", fontSize=17, leading=21, spaceAfter=4)
meta = ParagraphStyle("meta", parent=body, textColor=colors.HexColor("#444444"), spaceAfter=12)
h2 = ParagraphStyle("h2", fontName="Sans-Bold", fontSize=12.5, leading=16, spaceBefore=8, spaceAfter=6)
label = ParagraphStyle("label", parent=body, fontName="Sans-Bold", spaceAfter=2)


def code(s):
    return f'<font name="Mono">{s}</font>'


def codeb(s):
    return f'<font name="Mono-Bold">{s}</font>'


def bullets(items, style=body, kind="bullet", **kw):
    return ListFlowable(
        [ListItem(Paragraph(t, style) if isinstance(t, str) else t, leftIndent=18) for t in items],
        bulletType=kind, leftIndent=18, bulletFontName="Sans", bulletFontSize=10, **kw)


story = []
story += [
    Paragraph("Pixel Model Check", title),
    Paragraph("Oct 5, 2026 · @Bloodhound", meta),

    Paragraph("Why we're testing", h2),
    Paragraph(
        "This check shows, before we meet, whether the 10 GB on-device model runs smoothly on your "
        "<b>Pixel 10 Pro</b> (16 GB of memory) or runs out of memory. A model that doesn't fit gets "
        "re-read from storage on every answer: speed collapses, the phone heats up, or Android closes "
        "the app. PocketPal runs the same model file on the same engine (llama.cpp) as our app, so "
        "it's a fair stand-in.", body),
    Paragraph("It takes about 30 minutes, most of it the download.", body),

    Paragraph("Before you start:", label),
    bullets([
        "At least <b>12 GB of free storage</b>.",
        "Wi-Fi and a charger. The download is about 10 GB.",
        "Close other apps, so the numbers reflect the model rather than background load.",
    ]),

    Paragraph("Steps", h2),
    bullets([
        "Install <b>PocketPal AI</b> from the Play Store (free).",
        [Paragraph("Open <b>Settings</b> from the side menu and match our app before loading "
                   "anything. Leave everything not listed at its default.", body),
         bullets([
             "<b>Device Selection:</b> should read \"CPU only\". If it offers a GPU, choose <b>CPU</b>.",
             "<b>Context Size:</b> 4096",
             "<b>Advanced Settings</b> (tap to expand): <b>Flash Attention</b> → <b>Auto</b> "
             "(PocketPal's Android default is Off). CPU Threads stays at 4; Key and Value Cache "
             "Type stay at F16.",
             "<b>Memory Settings:</b> turn <b>Memory Mapping</b> on (PocketPal's Android default "
             "is off, which reads the whole file into memory up front; our app maps it from "
             "storage instead, and that is the behaviour this test measures). Leave "
             "<b>Use Memory Lock</b> off and <b>Enable Weight Repacking</b> on.",
         ], kind="bullet", start="–")],
        "Go to <b>Models</b>, tap <b>+</b> (bottom right) → <b>Add from Hugging Face</b>, and search "
        + code("Huihui-Qwen3-30B-A3B-Thinking-2507-abliterated-i1-GGUF")
        + ". Open the result from <b>mradermacher</b>.",
        "Download the file ending in " + codeb("i1-IQ2_M.gguf") + " (10.2 GB).",
        "Tap <b>Load</b> on the model. If PocketPal shows a <b>Memory Warning</b>, continue and "
        "note that it appeared. Note whether it loads and roughly how long it takes.",
        [Paragraph("Ask these 10 questions in one chat, one after another. After each answer, note "
                   "the <b>tokens/sec</b> figure PocketPal shows under the reply:", body),
         bullets([
             "Hi, how are you?",
             "Explain why ice floats on water.",
             "What's the capital of Australia?",
             "Give me three tips for sleeping better.",
             "Explain how a vaccine trains the immune system.",
             "Write a two-line poem about the sea.",
             "What is 17 × 23? Show your working.",
             "Summarise the plot of Romeo and Juliet in three sentences.",
             "Why do we have seasons?",
             "Hi again, what did I ask you first?",
         ], kind="a", bulletFormat="%s.")],
        "Note whether the phone got hot, and whether the app closed or froze.",
        "<b>Only if</b> the app crashed, froze, or question 10 was much slower than question 2: "
        "delete that model, download the file ending in " + codeb("i1-IQ2_S.gguf")
        + " (9.3 GB) from the same page, and repeat steps 5–7.",
    ], kind="1", bulletFormat="%s."),
]

# Results table
cell = ParagraphStyle("cell", parent=small)
hdr = ["Model file", "Loaded?", "Load time", "Speed Q2 (t/s)", "Speed Q5 (t/s)",
       "Speed Q10 (t/s)", "Crashed or froze?", "Phone hot?", "Notes"]
rows = [[Paragraph(h, cell) for h in hdr],
        [Paragraph("IQ2_M", cell)] + [""] * 8,
        [Paragraph("IQ2_S (only if needed)", cell)] + [""] * 8]
w = 7.0 * inch
cw = [0.85, 0.65, 0.6, 0.65, 0.65, 0.7, 0.75, 0.6]
cw = [c * inch for c in cw]
cw.append(w - sum(cw))
results = Table(rows, colWidths=cw, rowHeights=[None, 0.45 * inch, 0.6 * inch])
grid = TableStyle([
    ("GRID", (0, 0), (-1, -1), 0.6, colors.black),
    ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4),
    ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
])
results.setStyle(grid)

story.append(KeepTogether([
    Paragraph("What to send back", h2),
    Paragraph("Fill in what you can and send it back, with a screenshot of one reply showing its speed.", body),
    results,
    Spacer(1, 4),
    Paragraph("Also: Android version: ______ · PocketPal version: ______ · "
              "Memory Warning on load (yes/no): ______", small),
]))

meaning = Table([
    [Paragraph("Result", cell), Paragraph("What we do", cell)],
    [Paragraph("IQ2_M loads, about 5 t/s or faster, Q10 within roughly a third of Q2, no crash", cell),
     Paragraph("Fits. We build on IQ2_M.", cell)],
    [Paragraph("IQ2_M works but is slow, slows down over the 10 questions, or the phone gets very hot", cell),
     Paragraph("Borderline. We use IQ2_S, because our app also runs voice listening alongside the "
               "model and needs a little more memory than PocketPal.", cell)],
    [Paragraph("IQ2_M crashes or freezes, and IQ2_S does too", cell),
     Paragraph("Too big for this phone. We pick a smaller model together; nothing else changes.", cell)],
], colWidths=[w / 2, w / 2])
meaning.setStyle(grid)

story.append(KeepTogether([
    Paragraph("What the results mean", h2),
    meaning,
    Spacer(1, 6),
    Paragraph("PocketPal is a stand-in: same model file and engine, a different app. A pass here makes "
              "it very likely the model fits. The final confirmation comes from our app on the same "
              "phone, which logs speed and memory reloads for each answer.", body),
    Paragraph("<b>On speed:</b> treat PocketPal's tokens/sec as a guide to what this phone can do, not as "
              "our app's final figure. Both use the Pixel's fast CPU instructions (dot-product and int8 "
              "matrix maths). Our app is also being tuned specifically for the Pixel 10 Pro: it keeps the "
              "model on the fastest cores and reuses earlier parts of the conversation instead of "
              "re-reading them every reply, so its speeds can differ. Its own speed log on this phone is "
              "the number we go by.", body),
    Paragraph("The 5 t/s mark is a rough comfort line for reading answers as they appear, not a hard limit.", body),
]))

doc = SimpleDocTemplate(OUT, pagesize=letter, leftMargin=0.75 * inch, rightMargin=0.75 * inch,
                        topMargin=0.8 * inch, bottomMargin=0.8 * inch,
                        title="Pixel Model Check", author="@Bloodhound")
doc.build(story)
print(OUT)
