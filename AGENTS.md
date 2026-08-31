# Phone Control

## What the app is

Phone Control is a local MCP server that lets AI agents control approved Android apps from a computer. It exposes specific phone actions instead of giving agents access to an ADB shell.

The first version supports one connected Android phone and uses an app allowlist for authorization.

DHD is the phone assistant built around Phone Control. It helps the user do
things on their phone by understanding the request, keeping the user informed
about progress, and carrying out approved actions safely. DHD should stay
focused on the user's goal and verify the result before saying the task is
complete.

## DHD layers

1. **Assistant layer** — understands the user's request and decides what needs
   to happen.
2. **Companion layer** — coordinates the work and keeps the assistant connected
   to the phone.
3. **Phone layer** — observes the current phone state, enforces user control,
   and performs approved actions.
4. **App layer** — the Android apps where the user's task is carried out.

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
