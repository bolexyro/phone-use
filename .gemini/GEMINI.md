## Communication & Alignment Rules

- **Direct Feedback & Alignment**: When the user asks a question to check alignment or understanding (e.g., _"Do you get me?"_, _"Do you understand?"_), ALWAYS stop and answer the question directly in your text response first to confirm alignment before proceeding to tool execution or making assumptions.

- **HTML Briefings**: Rather than creating a `walkthrough.md` artifact, use the [$html-briefing](../.agents/skills/html-briefing/SKILL.md) skill.

## Git & Staging Discipline (CRITICAL)

- **Never blindly stage or commit all files** (e.g., NEVER run `git add -A`, `git add .`, or commit untargeted changes).
- Multiple agents or human collaborators may be working on separate modules of the repository simultaneously.
- **Stage only the exact files** you created or intentionally modified for your specific task:
  ```bash
  git add <exact-file-path-1> <exact-file-path-2>
  ```
- Always check `git status` and `git diff --staged` before committing to verify that no unrelated or concurrent workspace changes are bundled into your commit.
