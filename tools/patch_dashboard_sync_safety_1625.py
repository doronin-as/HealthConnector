from pathlib import Path

code_path = Path("apps-script/Code.gs")
code = code_path.read_text()
old_block = '''    const rebuildingDay = typeof dayComplete === 'boolean';
    if (rowNumber && !rebuildingDay) {
      row = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
    } else {
      if (!rowNumber) {
        rowNumber = sheet.getLastRow() + 1;
        existing.set(dateKey, rowNumber);
        if (rowNumber > 2) copyRowFormat_(sheet, rowNumber, headers.length);
      }
      // A checkpoint/final snapshot is a full rebuild of HC_Дни.
      // Clearing first prevents stale values from an older partial day.
      row = Array(headers.length).fill('');
      row[0] = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
    }
'''
new_block = '''    if (rowNumber) {
      // Preserve fields that this client could not read. availableFields below is
      // authoritative for fields that were actually read and may still clear a
      // stale value by sending null. This prevents a partial permission grant or
      // interrupted checkpoint from erasing a previously complete day.
      row = sheet.getRange(rowNumber, 1, 1, headers.length).getValues()[0];
    } else {
      rowNumber = sheet.getLastRow() + 1;
      existing.set(dateKey, rowNumber);
      if (rowNumber > 2) copyRowFormat_(sheet, rowNumber, headers.length);
      row = Array(headers.length).fill('');
      row[0] = Utilities.parseDate(`${dateKey} 00:00`, tz, 'yyyy-MM-dd HH:mm');
    }
'''
if code.count(old_block) != 1:
    raise SystemExit(f"Code.gs rebuild block count={code.count(old_block)}; refusing unsafe patch")
code = code.replace(old_block, new_block, 1)
code_path.write_text(code)

streamer_path = Path("app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt")
streamer = streamer_path.read_text()
old_line = "            dayComplete = date.isBefore(LocalDate.now(zone))\n"
new_line = "            dayComplete = date.isBefore(LocalDate.now(zone)) && permissionDeniedTypes.isEmpty()\n"
if streamer.count(old_line) != 1:
    raise SystemExit(f"HealthSyncStreamer dayComplete count={streamer.count(old_line)}; refusing unsafe patch")
streamer = streamer.replace(old_line, new_line, 1)
streamer_path.write_text(streamer)
