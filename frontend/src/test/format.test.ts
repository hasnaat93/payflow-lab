import { describe, expect, it } from "vitest";
import { qs } from "../lib/api";
import { endOfDayExclusiveIso, formatBps, formatMinor, formatMoney, humanize, parseMajorToMinor } from "../lib/format";

describe("formatMinor", () => {
  it.each([
    [0, "0.00"],
    [5, "0.05"],
    [99, "0.99"],
    [100, "1.00"],
    [123456, "1,234.56"],
    [100000000, "1,000,000.00"],
    [-2500, "-25.00"],
  ])("formats %i minor units as %s", (minor, expected) => {
    expect(formatMinor(minor)).toBe(expected);
  });

  it("spells out the currency so PKR and USD cannot be confused", () => {
    expect(formatMoney(1999, "USD")).toBe("USD 19.99");
    expect(formatMoney(150000, "PKR")).toBe("PKR 1,500.00");
    expect(formatMoney(null, "USD")).toBe("—");
  });
});

describe("parseMajorToMinor", () => {
  it.each([
    ["19.99", 1999], // parseFloat("19.99") * 100 would give 1998.9999999999998
    ["0.07", 7],
    ["1.1", 110],
    ["10", 1000],
    ["1,250.00", 125000],
    [" 49.5 ", 4950],
    ["1.15", 115],
    ["8.2", 820],
  ])("parses %s exactly to %i", (input, expected) => {
    expect(parseMajorToMinor(input)).toBe(expected);
  });

  it.each(["", "abc", "-5", "0", "0.00", "1.999", "1e3", "12.", ".50", "1 000"])("rejects %j", (input) => {
    expect(parseMajorToMinor(input)).toBeNull();
  });

  it("round-trips with formatMinor", () => {
    for (const minor of [1, 99, 100, 1999, 123456, 500000000]) {
      expect(parseMajorToMinor(formatMinor(minor))).toBe(minor);
    }
  });
});

describe("other helpers", () => {
  it("formats basis points as a percentage", () => {
    expect(formatBps(290)).toBe("2.90%");
    expect(formatBps(0)).toBe("0.00%");
  });

  it("humanizes enum names", () => {
    expect(humanize("PARTIALLY_REFUNDED")).toBe("Partially refunded");
    expect(humanize(null)).toBe("—");
  });

  it("builds an exclusive upper bound that includes the whole day", () => {
    expect(endOfDayExclusiveIso("2026-03-31")).toBe("2026-04-01T00:00:00.000Z");
  });

  it("omits empty query parameters", () => {
    expect(qs({ q: "", status: "CAPTURED", page: 0, merchantId: null, from: undefined })).toBe("?status=CAPTURED&page=0");
    expect(qs({})).toBe("");
  });
});
