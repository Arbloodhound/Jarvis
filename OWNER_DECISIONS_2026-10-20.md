# Owner decisions — 2026-10-20

Two items from HANDOFF_2026-10-20.md "Waiting on the owner" are decided:

1. **Action model: KEEP IT LOADED.** 4 cores, kept resident in memory (about 1.3 GB more than
   load-on-demand). The owner picked this over the as-built load-on-demand behaviour — the
   61-second cold first tool call is the reason. Change the action model to load once and stay
   loaded alongside the main model; the Settings "Action model" behaviour should reflect that.
2. **Do NOT drop the second test run on GitHub.** The double test run stays, even though it
   costs about 2 minutes per build. No change to the release workflow on this point.

Still waiting on the owner: the DIAG run on the new build (for the RAM breakdown) and the
20-task phone checklist results.
