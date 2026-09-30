FOUNDATIONS CLUSTER 0.0.5a.R7.R5 UPDATE

1) Shut down the entire cluster from the dashboard.
2) Extract this ZIP over the existing Cluster folder and allow replacement.
3) Run APPLY_R7_R5_UPDATE.bat.
4) Start normally with start_foundations_server.bat.
5) Confirm Manager and all workers report 0.0.5a.R7.R5.

R7.R5 adds generic restart-durable BlockEntity persistence. This is not chest-
specific: full normal Minecraft/NeoForge BlockEntity serialization is stored and
replicated, including compatible modded state, components, and attachments.

Workers still keep separate physical world directories. Do not point multiple
JVMs at one writable Minecraft world folder.

Primary test: fill a vanilla chest and a modded container/machine, roam across
workers/layers, modify them, shut down the whole cluster, restart, and verify the
newest contents/state remains.
