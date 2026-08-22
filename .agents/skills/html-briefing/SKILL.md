---
name: html-briefing
description: Use this to create a browser-ready HTML briefing when the user asks for a brief, update notes, or an explanation of work completed; save it under ~/.codex/html-briefings unless the user explicitly gives another destination.
---

# HTML briefing

Use this skill when the user asks to be briefed on work that was done, asks what changed, requests update notes, or asks for an HTML explanation of changes. The deliverable is an HTML file, not only a prose response.

## Output location

- Resolve the default destination as `$CODEX_HOME/bolexyro-html-briefings/<project-name>` when `CODEX_HOME` is set; otherwise use `~/.codex/bolexyro-html-briefings/<project-name>` 
- Create the destination directory when it does not exist.
- Do not put the briefing in the current project or repository unless the user explicitly requests that location.
- Use a descriptive, kebab-case filename such as `<topic>-briefing.html`.

Make the HTML self-contained and easy to read in a browser.

## Completion response

After writing the file, return a clickable absolute local link to the created HTML file. 
