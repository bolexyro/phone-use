# Phone Control

## What the app is

Phone Control is a local MCP server that lets AI agents control approved Android apps from a computer. It exposes specific phone actions instead of giving agents access to an ADB shell.

The first version supports one connected Android phone and uses an app allowlist for authorization. It does not include an Android companion app.

## How it works

1. The user connects an Android phone through USB or wireless debugging.
2. The MCP server uses ADB to inspect the device, launch apps, capture screenshots, and send input.
3. UI Automator data identifies visible controls when the app exposes useful accessibility information.
4. The agent calls MCP tools to observe the screen, open an approved app, tap, swipe, type, or press a key.
5. The server checks the foreground Android package before each action and rejects access to apps outside the allowlist.
6. scrcpy provides a live mirror for the user to watch or take over manually.

## Tech stack

- TypeScript and Node.js
- MCP TypeScript SDK with Zod schemas
- ADB for Android device commands
- scrcpy for screen mirroring
- UI Automator for visible UI metadata

