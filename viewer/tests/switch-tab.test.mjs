import test from "node:test";
import assert from "node:assert/strict";
import { switchTab } from "../viewer.js";

test("switchTab('history') không ném lỗi (switchHistorySubtab phải ở phạm vi module)", () => {
  assert.doesNotThrow(() => switchTab("history"));
});
