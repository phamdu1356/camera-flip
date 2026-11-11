import test from "node:test";
import assert from "node:assert/strict";
import { formatBatteryTemp, formatLiveFps, formatLiveRate } from "../viewer.js";

test("formatBatteryTemp hiển thị một chữ số thập phân", () => {
  assert.equal(formatBatteryTemp(36.04), "36.0°C");
  assert.equal(formatBatteryTemp(41.5), "41.5°C");
});
test("formatBatteryTemp xử lý giá trị không hợp lệ", () => {
  assert.equal(formatBatteryTemp(NaN), "--°C");
  assert.equal(formatBatteryTemp(0), "--°C");
});

test("formatLiveFps và formatLiveRate", () => {
  assert.equal(formatLiveFps(29.6), "30 fps");
  assert.equal(formatLiveFps(NaN), "-- fps");
  assert.equal(formatLiveRate(6334), "6.3 Mbps");
  assert.equal(formatLiveRate(480), "480 kbps");
  assert.equal(formatLiveRate(NaN), "-- Mbps");
});
