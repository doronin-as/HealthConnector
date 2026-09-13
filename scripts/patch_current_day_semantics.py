from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"{label}: target not found")
    return text.replace(old, new, 1)

# Current day is inherently partial until the next calendar day.
p = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
t = p.read_text()
t = replace_once(
    t,
    '            dayComplete = true\n',
    '            dayComplete = date.isBefore(LocalDate.now(zone))\n',
    'current day completion marker'
)
p.write_text(t)

# Ignore history older than Health Connect can repair (30 days), so an ancient gap
# cannot force every sync back to the oldest readable date forever.
p = Path('apps-script/Code.gs')
t = p.read_text()
t = replace_once(
    t,
    "  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');\n  const fallbackDays = Math.max(1, Math.min(30, Number(payload.fallbackDays || 7)));\n",
    "  const today = Utilities.formatDate(new Date(), tz, 'yyyy-MM-dd');\n  const oldestRepairable = addDaysToDateKeyV3_(today, -29, tz);\n  const fallbackDays = Math.max(1, Math.min(30, Number(payload.fallbackDays || 7)));\n",
    'repairable cutoff declaration'
)
t = replace_once(
    t,
    "    if (!date || date > today) return;\n",
    "    if (!date || date < oldestRepairable || date > today) return;\n",
    'repairable row filter'
)
p.write_text(t)
