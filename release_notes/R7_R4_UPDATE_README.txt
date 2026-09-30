Foundations Cluster Runtime 0.0.5a.R7.R4 UPDATE

1. Shut the Foundations Cluster down from the dashboard.
2. Extract this update over the ROOT of your existing Cluster folder and allow replacement.
3. Run APPLY_R7_R4_UPDATE.bat once.
4. Start normally with start_foundations_server.bat.
5. Confirm Manager and Cluster Node both report 0.0.5a.R7.R4.
6. Test: place/change blocks -> roam workers -> shut down entire cluster -> restart -> verify latest blocks remain.

The updater preserves existing worker world folders and runtime data. It installs the R7.R4 node JAR into the server template and all existing worker mod folders, and appends missing persistence settings without replacing your live cluster.properties.

R7.R4 scope: restart-durable ordinary block states. Chests/block entities/entities/POI and arbitrary mod SavedData are NOT yet claimed restart-durable by this revision.
