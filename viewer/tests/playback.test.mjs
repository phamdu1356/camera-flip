import test from "node:test";
import assert from "node:assert/strict";
import {
  PLAYBACK_SPEEDS,
  TIMEZONE_UTC7,
  UTC7_OFFSET_MS,
  boundaryClassification,
  formatTimestamp,
  getUtc7Calendar,
  historyWindow,
  nextChronologicalSegment,
  normalizePlaybackRate,
} from "../viewer.js";

test("historyWindow anchors to Asia/Bangkok (UTC+7) and rejects impossible dates or hours", () => {
  // 08:00 on 2026-10-31 in UTC+7 is 01:00 UTC (1793408400000)
  const expectedFrom = Date.UTC(2026, 9, 31, 1, 0, 0, 0);
  const expectedTo = Date.UTC(2026, 9, 31, 2, 0, 0, 0);
  assert.equal(expectedFrom, 1793408400000);
  assert.deepEqual(historyWindow("2026-10-31", "8"), { from: expectedFrom, to: expectedTo });
  assert.deepEqual(historyWindow("2026-10-31", 8), { from: expectedFrom, to: expectedTo });

  // Midnight 00:00 on 2026-10-31 in UTC+7 is 17:00 UTC on 2026-10-30
  const midnightFrom = Date.UTC(2026, 9, 30, 17, 0, 0, 0);
  assert.deepEqual(historyWindow("2026-10-31", "0"), { from: midnightFrom, to: midnightFrom + 3600000 });

  // 23:00 on 2026-10-31 in UTC+7 is 16:00 UTC on 2026-10-31
  const lateFrom = Date.UTC(2026, 9, 31, 16, 0, 0, 0);
  assert.deepEqual(historyWindow("2026-10-31", 23), { from: lateFrom, to: lateFrom + 3600000 });

  // Valid leap year check
  const leapWindow = historyWindow("2024-02-29", 12);
  assert.notEqual(leapWindow, null);
  assert.equal(leapWindow.from, Date.UTC(2024, 1, 29, 5, 0, 0, 0));

  // Rejections of impossible inputs
  const invalidInputs = [
    ["2026-02-30", 8],
    ["2026-04-31", 8],
    ["2025-02-29", 12],
    ["", 8],
    ["not-a-date", 8],
    ["2026-10-31", -1],
    ["2026-10-31", 24],
    ["2026-10-31", "abc"],
    ["2026-10-31", null],
  ];
  for (const [date, hour] of invalidInputs) {
    assert.equal(historyWindow(date, hour), null, `Expected null for ${date} hour ${hour}`);
  }
});

test("getUtc7Calendar decomposes any UTC millisecond timestamp to Asia/Bangkok local components", () => {
  // 01:00 UTC on 2026-10-31 -> 08:00:00 on 2026-10-31 in UTC+7
  const utcMorning = Date.UTC(2026, 9, 31, 1, 0, 0, 0);
  const morningCal = getUtc7Calendar(utcMorning);
  assert.deepEqual(morningCal, {
    year: 2026,
    month: 10,
    day: 31,
    hour: 8,
    minute: 0,
    second: 0,
    dateString: "2026-10-31",
  });

  // Rollover: 16:59:59 UTC on 2026-10-31 -> 23:59:59 on 2026-10-31 in UTC+7
  const beforeMidnight = Date.UTC(2026, 9, 31, 16, 59, 59, 0);
  assert.equal(getUtc7Calendar(beforeMidnight).dateString, "2026-10-31");
  assert.equal(getUtc7Calendar(beforeMidnight).hour, 23);

  // Rollover: 17:00:00 UTC on 2026-10-31 -> 00:00:00 on 2026-11-01 in UTC+7
  const afterMidnight = Date.UTC(2026, 9, 31, 17, 0, 0, 0);
  assert.equal(getUtc7Calendar(afterMidnight).dateString, "2026-11-01");
  assert.equal(getUtc7Calendar(afterMidnight).day, 1);
  assert.equal(getUtc7Calendar(afterMidnight).hour, 0);
});

test("formatTimestamp outputs in Asia/Bangkok timezone regardless of local system timezone", () => {
  const ts = Date.UTC(2026, 9, 31, 1, 4, 37, 0); // 08:04:37 in UTC+7
  const formatted = formatTimestamp(ts);
  assert.match(formatted, /08:04/);
  assert.match(formatted, /31/);

  assert.equal(formatTimestamp(0), "Thời gian không rõ");
  assert.equal(formatTimestamp(-100), "Thời gian không rõ");
  assert.equal(formatTimestamp(NaN), "Thời gian không rõ");
});

test("UTC+7 search remains valid through New York's missing spring hour", () => {
  const range = historyWindow("2026-03-08", 2);
  assert.deepEqual(range, {
    from: Date.parse("2026-03-07T19:00:00Z"),
    to: Date.parse("2026-03-07T20:00:00Z"),
  });
  assert.equal(range.to - range.from, 3_600_000);
  // At the actual US transition instant, Bangkok advances normally.
  assert.equal(getUtc7Calendar(Date.parse("2026-03-08T06:59:59Z")).hour, 13);
  assert.equal(getUtc7Calendar(Date.parse("2026-03-08T07:00:00Z")).hour, 14);
});

test("UTC+7 search has one unambiguous hour through New York's fall repeat", () => {
  assert.deepEqual(historyWindow("2026-11-01", 1), {
    from: Date.parse("2026-10-31T18:00:00Z"),
    to: Date.parse("2026-10-31T19:00:00Z"),
  });
  // Two instants that share 01:30 in New York stay distinct in the viewer.
  assert.equal(getUtc7Calendar(Date.parse("2026-11-01T05:30:00Z")).hour, 12);
  assert.equal(getUtc7Calendar(Date.parse("2026-11-01T06:30:00Z")).hour, 13);
});

test("UTC+7 defaults, displayed date and search agree across midnight and year rollover", () => {
  for (const [instant, date, year] of [
    ["2026-10-31T17:05:00Z", "2026-11-01", 2026],
    ["2026-12-31T17:05:00Z", "2027-01-01", 2027],
  ]) {
    const timestamp = Date.parse(instant);
    const calendar = getUtc7Calendar(timestamp);
    assert.equal(calendar.dateString, date);
    assert.equal(calendar.hour, 0);
    assert.equal(calendar.minute, 5);
    const range = historyWindow(calendar.dateString, calendar.hour);
    assert.equal(range.from, timestamp - 5 * 60_000);
    assert.equal(range.to, timestamp + 55 * 60_000);
    const formatted = formatTimestamp(timestamp);
    assert.match(formatted, /00:05/);
    assert.ok(formatted.includes(String(year)));
    assert.equal(formatTimestamp(timestamp), formatTimestamp(range.from + 5 * 60_000));
  }
});

test("UTC+7 calendar validates Gregorian leap-century boundaries", () => {
  assert.equal(historyWindow("2100-02-29", 0), null);
  assert.equal(historyWindow("2026-02-29", 0), null);
  assert.equal(historyWindow("2028-02-29", 0).from, Date.parse("2028-02-28T17:00:00Z"));
  assert.equal(historyWindow("2000-02-29", 0).from, Date.parse("2000-02-28T17:00:00Z"));
});

test("boundaryClassification identifies cross-boundary and spanning segments", () => {
  const windowRange = { from: 10_000, to: 20_000 };

  // Fully inside: [11_000, 15_000]
  assert.equal(boundaryClassification({ startUtc: 11_000, endUtc: 15_000 }, windowRange), "inside");

  // Crossing from previous hour: [9_000, 11_000]
  assert.equal(boundaryClassification({ startUtc: 9_000, endUtc: 11_000 }, windowRange), "crossing_previous");

  // Crossing into next hour: [19_000, 21_000]
  assert.equal(boundaryClassification({ startUtc: 19_000, endUtc: 21_000 }, windowRange), "crossing_next");

  // Spanning entire hour: [9_000, 21_000]
  assert.equal(boundaryClassification({ startUtc: 9_000, endUtc: 21_000 }, windowRange), "spanning");

  // Missing or null window returns "inside" safely
  assert.equal(boundaryClassification({ startUtc: 9_000, endUtc: 11_000 }, null), "inside");
});

const current = {
  id: "00000000-0000-4000-8000-000000000002",
  startUtc: 2_000,
};

test("playback speed offers usable rates and normalizes invalid values", () => {
  assert.deepEqual(PLAYBACK_SPEEDS, [0.5, 0.75, 1, 1.25, 1.5, 2]);
  assert.equal(normalizePlaybackRate("1.5"), 1.5);
  assert.equal(normalizePlaybackRate("not-a-rate"), 1);
  assert.equal(normalizePlaybackRate(16), 1);
});

test("next playback segment is the nearest later segment regardless of API ordering", () => {
  const later = {
    id: "00000000-0000-4000-8000-000000000004",
    startUtc: 4_000,
  };
  const nearest = {
    id: "00000000-0000-4000-8000-000000000003",
    startUtc: 3_000,
  };
  const earlier = {
    id: "00000000-0000-4000-8000-000000000001",
    startUtc: 1_000,
  };
  assert.equal(
    nextChronologicalSegment(current, [later, earlier, nearest]),
    nearest,
  );
});

test("chronology uses the UUID tie-breaker and ignores invalid rows", () => {
  const sameTimeNext = {
    id: "00000000-0000-4000-8000-000000000003",
    startUtc: 2_000,
  };
  const sameTimeBefore = {
    id: "00000000-0000-4000-8000-000000000001",
    startUtc: 2_000,
  };
  assert.equal(
    nextChronologicalSegment(current, [
      { id: "invalid", startUtc: 2_001 },
      { id: "00000000-0000-4000-8000-000000000005", startUtc: "invalid" },
      sameTimeBefore,
      sameTimeNext,
    ]),
    sameTimeNext,
  );
});

test("playback stops at the end of the available chronological list", () => {
  assert.equal(nextChronologicalSegment(current, [current]), null);
  assert.equal(nextChronologicalSegment({ id: "invalid", startUtc: 2_000 }, []), null);
});
