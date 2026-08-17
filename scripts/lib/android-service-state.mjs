export function findServiceRecord(dump, component) {
  const lines = dump.split(/\r?\n/);
  let recordLines = null;
  let recordIndentLength = 0;

  for (const line of lines) {
    const recordStart = line.match(/^(\s*)\* ServiceRecord\{/);
    if (recordStart) {
      if (recordLines?.[0].includes(component)) return recordLines.join("\n");
      recordLines = [line];
      recordIndentLength = recordStart[1].length;
      continue;
    }

    if (recordLines == null) continue;
    const contentStart = line.match(/^(\s*)\S/);
    if (contentStart && contentStart[1].length <= recordIndentLength) {
      if (recordLines[0].includes(component)) return recordLines.join("\n");
      recordLines = null;
      continue;
    }
    recordLines.push(line);
  }

  return recordLines?.[0].includes(component) ? recordLines.join("\n") : null;
}

export function serviceRecordIsBound(dump, component) {
  const record = findServiceRecord(dump, component);
  return record != null && /requested=true received=true hasBound=true/.test(record);
}

export function hasDeadServiceConnection(dump, component) {
  return dump.split(/\r?\n/).some((line) =>
    line.includes(component) && /\bDEAD\b/.test(line)
  );
}
