# 0.0.6a Build Validation

Validation performed in the build environment before packaging:

- Manager compiled with Java 21: PASS.
- Dashboard JavaScript syntax (`node --check web/app.js`): PASS.
- NeoForge Cluster Node source compiled with Java 21 against NeoForge 21.1.250/server libraries: PASS.
- Cluster Node JAR repackaged with `META-INF/neoforge.mods.toml`: PASS.
- Node JAR contains no bundled `net/minecraft/*` classes: PASS.
- RAM budget harness: 128 GiB total, 20 GiB reserve, 12 GiB/worker => effective max 9: PASS.
- RAM budget harness: configured maxWorkers=7 overrides RAM-derived 9 => effective max 7: PASS.

Runtime acceptance still requires the live tests in `6A_INTEGRATION_CHECKLIST.md`, especially autoscaling/consolidation, seed identity on the user's actual modpack, and player evacuation under load.
- Drop-in overlay smoke test over the R7.R6 update layout: PASS.
- Package does not bundle/overwrite `config/cluster.properties`: PASS.
- ZIP integrity (`unzip -t`): PASS.
