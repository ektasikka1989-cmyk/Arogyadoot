# ArogyaDoot GitHub README + screenshots update (v1.0.0)

The `.patch` file adds 8 genuine screenshots, a complete README draft at
`docs/github/README-v1.0.0.md`, and a PowerShell installer at
`tools/apply-readme-v1.ps1`. It deliberately does **not** overwrite an existing
`README.md` as part of `git apply`, because its current contents were unavailable
and a guessed text patch could fail or silently remove later changes.

## Apply on Windows

1. Save `ArogyaDoot_README_Screenshots_v1.0.0.patch` into `C:\Arogyadoot`.
2. In a Windows PowerShell terminal:

```powershell
cd C:\Arogyadoot
git status --short
git apply --check .\ArogyaDoot_README_Screenshots_v1.0.0.patch
git apply .\ArogyaDoot_README_Screenshots_v1.0.0.patch
powershell -ExecutionPolicy Bypass -File .\tools\apply-readme-v1.ps1
git diff -- README.md
git status --short
```

3. Inspect `README.md` in GitHub/Markdown preview. Check the screenshots and
   confirm that all displayed patient and ASHA names are **fictional test data**
   or otherwise authorised for public publication.
4. Confirm the app builds and relevant docs accurately describe the installed
   version. No source files are modified by this package.
5. Stage **only** the intended README, screenshots, and supporting files:

```powershell
git add README.md docs\github\README-v1.0.0.md docs\screenshots tools\apply-readme-v1.ps1
git diff --cached --stat
git diff --cached --check
git commit -m "docs: publish ArogyaDoot 1.0.0 README and screenshots"
git remote -v
git branch --show-current
```

6. After confirming `origin` is **only**
   `https://github.com/ektasikka1989-cmyk/Arogyadoot.git`, push your current
   branch if you intend to publish the changes:

```powershell
git push -u origin main
```

If your active branch is not `main`, do not blindly run the last command;
check the branch and remote first.

## Repository About fields

- **Description:** `Offline-first AI field assistant for ASHA workers: cited SOP guidance, visits, follow-ups and field reporting.`
- **Website:** leave blank until an ArogyaDoot site exists.
- **Topics:** `android`, `kotlin`, `healthcare`, `digital-health`, `asha-workers`, `offline-ai`, `gemma`, `rag`, `ai-assistant`, `india`
- **Release tag suggestion:** `v1.0.0` (only after verifying the release APK and installation).
- **License:** do not select an open-source license until reuse rights have been decided.

## Rollback of documentation only

The installer copies the previous README to an external timestamped directory
under the Windows TEMP directory before replacement. If the staged update is
not yet committed, use that backup to recover the prior README. Do not reset
the application source or clear Android app data.
