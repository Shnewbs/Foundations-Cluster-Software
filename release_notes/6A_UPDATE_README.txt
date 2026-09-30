FOUNDATIONS CLUSTER RUNTIME 0.0.6a UPDATE

INSTALL
1. Shut down the ENTIRE Foundations cluster from the dashboard.
2. Extract this ZIP over the existing Cluster directory and allow replacement.
3. Run APPLY_6A_UPDATE.bat.
4. Start normally with start_foundations_server.bat.
5. Confirm the Manager and every active worker report 0.0.6a.

FIRST BOOT RECOMMENDATION
- Leave scaling.mode=manual for the first boot.
- Confirm every worker reports the same cluster seed and World Identity MATCH.
- Switch to scaling.mode=assisted and test one scale-up + one consolidation.
- Then enable scaling.mode=automatic if the behavior is correct for your server.

IMPORTANT UPGRADE BEHAVIOR
The updater keeps world.identity.enforce=false on an upgraded existing cluster unless
that key already exists. This is intentional. Existing worker worlds may have been
created before the cluster seed was locked. Once all healthy workers report MATCH,
you can enable enforcement from the dashboard.

MEMORY EXAMPLE
128 GiB budget, 20 GiB system reserve, 12 GiB worker reservation:
(128 - 20) / 12 = 9 worker JVM slots.
If scaling.maxWorkers=7, the effective maximum is 7.

0.0.6a DOES NOT
- Point multiple Minecraft JVMs at the same physical world directory.
- Guarantee a completely invisible Minecraft backend switch. Automatic scale-down
  prefers consolidation/empty-first behavior and reuses the R7.R6 graceful handoff.
