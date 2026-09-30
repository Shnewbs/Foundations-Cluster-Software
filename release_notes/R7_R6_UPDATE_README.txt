FOUNDATIONS CLUSTER RUNTIME 0.0.5a.R7.R6 UPDATE

1. Shut the ENTIRE Foundations cluster down from the dashboard.
2. Extract this ZIP over the existing Cluster directory and allow replacement.
3. Run APPLY_R7_R6_UPDATE.bat.
4. Start normally with start_foundations_server.bat.
5. Confirm Manager + active workers report 0.0.5a.R7.R6.

R7.R6 adds:
- 10-second graceful Stop evacuation with Cancel Stop and separate Force Stop.
- batched player handoffs during worker drain.
- authority-region evacuation as part of Stop.
- cluster-wide playerdata / advancements / statistics profile staging.
- configurable extra per-player shared files.

The updater does NOT delete worker worlds or R7.R4/R7.R5 persistence journals.
The existing R7.R2 Velocity gateway plugin remains compatible and can stay in
place.
