Foundations Cluster 0.0.7a-dev.2.R2 / Node 0.0.6a.R16
INSTALLABLE DEVELOPMENT REVISION: paired vanilla cow/item views.
DISPLAY ONLY: no entity ownership transfer, remote pickup or remote Survival edits.

Stop Manager, gateway and every worker. Use a stopped backup/test copy. Extract
separately and run APPLY_UPDATE.bat, selecting the existing stopped Cluster folder.
Keep the printed backup path. Start normally and confirm Node R16 on every worker.
No additional mod ID or client mod is needed for the new presentation packets.

Accepts HF11-HF16, original dev.2 or exact dev.2.R2. Worlds, player saves, profiles,
configuration, credentials, journals, reports and pair selections are excluded
from the updater payload. Later Node synchronization and gateway runtime updates
have their own separate backups.

Read ENTITY_VIEWS_GUIDE.md first. Pair an entity on its ACTIVE source region owner,
then opt in with /foundations_entities view on. Keep the source online and its
region ownership unchanged. This is not a migration/failover test. Remote views
cannot be interacted with; native entities are never merged by location.

PLAYTEST_GUIDE.md retains the prior dev.2 observer commands and report instructions.
The recorder format itself has not changed. Match the installed package/Node to
Rev2 as specified in ENTITY_VIEWS_GUIDE.md, not the older guide's version examples.
