import json
import os
from datetime import datetime, timezone
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm

# Load session data
with open("session_log.json", "r") as f:
    session = json.load(f)

# Output path
os.makedirs("reports", exist_ok=True)
now = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
username_clean = session["user_name"].replace(" ", "_").lower()
pdf_path = f"reports/auditc_{username_clean}_{now}.pdf"
txt_path = f"reports/auditc_{username_clean}_{now}.txt"

# --- Generate TXT ---
lines = []
lines.append("=" * 48)
lines.append("  AUDIT C — LAB QUALITY CONTROL REPORT")
lines.append("=" * 48)
lines.append(f"  Technician : {session['user_name']}")
lines.append(f"  Login time : {session['user_name_timestamp']}")
lines.append(f"  Generated  : {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}")
lines.append("=" * 48)
lines.append("")
lines.append("STEP RESULTS")
lines.append("-" * 48)
lines.append(f"  Step 0 — Technician Name")
lines.append(f"    Input     : {session['user_name']}")
lines.append(f"    Timestamp : {session['user_name_timestamp']}")
lines.append(f"    Status    : PASS")
lines.append("")
for step in session["steps"]:
    status = step["status"].upper() if step["status"] != "pending" else "PENDING"
    lines.append(f"  Step {step['step_number']} — {step['step_name']}")
    lines.append(f"    Input     : {step['voice_input'] or '(no input)'}")
    lines.append(f"    Timestamp : {step['timestamp'] or '(not completed)'}")
    lines.append(f"    Status    : {status}")
    lines.append("")
lines.append("=" * 48)
lines.append("  END OF REPORT")
lines.append("=" * 48)

with open(txt_path, "w") as f:
    f.write("\n".join(lines))
print(f"TXT saved: {txt_path}")

# --- Generate PDF ---
doc = SimpleDocTemplate(pdf_path, pagesize=A4,
    rightMargin=2*cm, leftMargin=2*cm,
    topMargin=2*cm, bottomMargin=2*cm)

styles = getSampleStyleSheet()
title_style = ParagraphStyle('title', fontSize=16, fontName='Helvetica-Bold', spaceAfter=6)
sub_style   = ParagraphStyle('sub',   fontSize=10, fontName='Helvetica', spaceAfter=4, textColor=colors.HexColor('#444444'))
head_style  = ParagraphStyle('head',  fontSize=12, fontName='Helvetica-Bold', spaceAfter=6, spaceBefore=12)

story = []
story.append(Paragraph("AUDIT C — Lab Quality Control Report", title_style))
story.append(Paragraph(f"Technician: <b>{session['user_name']}</b>", sub_style))
story.append(Paragraph(f"Login time: {session['user_name_timestamp']}", sub_style))
story.append(Paragraph(f"Generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}", sub_style))
story.append(Spacer(1, 0.4*cm))

# Table
table_data = [["Step", "Name", "Input", "Timestamp", "Status"]]
# Step 0
table_data.append(["0", "Technician Name", session["user_name"], session["user_name_timestamp"], "PASS"])
# Steps 1-6
for step in session["steps"]:
    status = step["status"].upper() if step["status"] != "pending" else "PENDING"
    table_data.append([
        str(step["step_number"]),
        step["step_name"],
        step["voice_input"] or "(no input)",
        step["timestamp"] or "(not completed)",
        status
    ])

table = Table(table_data, colWidths=[1*cm, 4*cm, 3*cm, 4.5*cm, 2*cm])
table.setStyle(TableStyle([
    ('BACKGROUND', (0,0), (-1,0), colors.HexColor('#2C2C2A')),
    ('TEXTCOLOR',  (0,0), (-1,0), colors.white),
    ('FONTNAME',   (0,0), (-1,0), 'Helvetica-Bold'),
    ('FONTSIZE',   (0,0), (-1,-1), 9),
    ('ROWBACKGROUNDS', (0,1), (-1,-1), [colors.HexColor('#F1EFE8'), colors.white]),
    ('TEXTCOLOR',  (0,1), (-1,-1), colors.HexColor('#2C2C2A')),
    ('GRID',       (0,0), (-1,-1), 0.5, colors.HexColor('#B4B2A9')),
    ('PADDING',    (0,0), (-1,-1), 6),
    ('ALIGN',      (4,1), (4,-1), 'CENTER'),
]))
story.append(table)
story.append(Spacer(1, 0.4*cm))
story.append(Paragraph("End of Report", sub_style))
doc.build(story)
print(f"PDF saved: {pdf_path}")
