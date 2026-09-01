import { cpSync, existsSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = resolve(fileURLToPath(new URL("../", import.meta.url)));

for (const dirName of ["companion-web"]) {
  const sourceDirectory = resolve(projectRoot, "src", dirName);
  const outputDirectory = resolve(projectRoot, "dist", dirName);

  if (existsSync(sourceDirectory)) {
    mkdirSync(outputDirectory, { recursive: true });
    for (const file of ["index.html", "styles.css", "favicon.png"]) {
      const sourceFile = resolve(sourceDirectory, file);
      if (existsSync(sourceFile)) {
        cpSync(sourceFile, resolve(outputDirectory, file));
      }
    }
  }
}
