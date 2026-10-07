---
description: Delegate task execution sequentially to Gemini (agy) or OpenCode
---

Write the task specification into `.agent/tasks/active_task.md` based on:
$ARGUMENTS

Then run the sequential dispatch script:
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-worker.ps1 -Engine gemini
```

After the worker finishes:
1. Run `git diff` to review all modifications.
2. Run the relevant test or build verification.
3. Summarize the changes and test results.
