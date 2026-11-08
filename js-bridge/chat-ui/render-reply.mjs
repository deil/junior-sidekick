import { compileSpecStream, parseSpecStreamLine } from "@json-render/core";
import { toCard } from "@json-render/chat";
import { cardToBlockKit, cardToFallbackText } from "@chat-adapter/slack";
import { cardToDiscordPayload } from "@chat-adapter/discord";

const converters = {
  slack: (card) => ({ blocks: cardToBlockKit(card) }),
  discord: (card) => cardToDiscordPayload(card),
};

// Process a completed reply. Other fenced code blocks, including examples that
// contain nested fences, remain ordinary text.
export function renderReply(platform, text) {
  const convert = converters[platform];
  if (!convert) throw new Error(`Unsupported chat UI platform: ${platform}`);

  const parts = [];
  const opening = /^ {0,3}(`{3,}|~{3,})([^\r\n]*)\r?\n/gm;
  let consumed = 0;
  let match;
  while ((match = opening.exec(text))) {
    const fence = match[1];
    const closing = new RegExp(`^ {0,3}${fence[0]}{${fence.length},}[ \\t]*\\r?$`, "gm");
    closing.lastIndex = opening.lastIndex;
    const end = closing.exec(text);
    if (!end) break;

    const bodyStart = opening.lastIndex;
    opening.lastIndex = closing.lastIndex;
    if (match[2].trim() !== "spec") continue;

    if (match.index > consumed) parts.push({ text: text.slice(consumed, match.index) });
    try {
      const jsonl = text.slice(bodyStart, end.index);
      for (const line of jsonl.split("\n").filter((line) => line.trim())) {
        if (!parseSpecStreamLine(line)) throw new Error("Invalid spec patch line.");
      }
      const spec = compileSpecStream(jsonl);
      const card = toCard(spec);
      parts.push({ payload: convert(card), fallback_text: cardToFallbackText(card) });
    } catch (error) {
      console.error(`Chat UI rendering failed: ${error.message}`);
      parts.push({ text: "[Visualization could not be rendered.]" });
    }
    consumed = closing.lastIndex;
  }
  if (consumed < text.length) parts.push({ text: text.slice(consumed) });
  return parts;
}

let input = "";
for await (const chunk of process.stdin) input += chunk;
const { platform, text } = JSON.parse(input);
process.stdout.write(JSON.stringify(renderReply(platform, text)) + "\n");
