import { catalog } from "./catalog.mjs";

process.stdout.write(catalog.prompt({ mode: "inline" }) + "\n");
