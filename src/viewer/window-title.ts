export function parsePackageFromWindowTitle(title: string): string | undefined {
  const match = title.match(/^Phone Control:\s*([a-zA-Z0-9_.-]+)$/i);
  return match ? match[1].trim() : undefined;
}
