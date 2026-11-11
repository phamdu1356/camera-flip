// Single USTAR download: Linux Archive Manager and `tar -xf` can open it.
export function makeArchive(items) {
  const parts = [];
  const encoder = new TextEncoder();
  for (const item of items) {
    const header = new Uint8Array(512);
    const text = (offset, length, value) =>
      header.set(encoder.encode(value).slice(0, length), offset);
    const octal = (offset, length, value) =>
      text(
        offset,
        length,
        `${Math.floor(value)
          .toString(8)
          .padStart(length - 1, "0")}\0`,
      );
    text(0, 100, item.name);
    octal(100, 8, 0o644);
    octal(108, 8, 0);
    octal(116, 8, 0);
    octal(124, 12, item.blob.size);
    octal(136, 12, item.createdAt / 1000);
    header.fill(32, 148, 156);
    header[156] = 48;
    text(257, 6, "ustar\0");
    text(263, 2, "00");
    const checksum = header.reduce((total, value) => total + value, 0);
    text(148, 8, `${checksum.toString(8).padStart(6, "0")}\0 `);
    parts.push(header, item.blob);
    const padding = (512 - (item.blob.size % 512)) % 512;
    if (padding) parts.push(new Uint8Array(padding));
  }
  parts.push(new Uint8Array(1024));
  return new Blob(parts, { type: "application/x-tar" });
}
