# Foundations Cluster — Source Baseline and Artifact Manifest

**Baseline:** `0.0.6a.R19-HF11` / Node `0.0.6a.R13`

## Official source package used to create this handoff

```text
FoundationsCluster-0.0.6a.R19-HF11-SOURCE.zip
SHA-256: a472f5c2c022e6f4dc279b56b501fdacdc75cd8bdcdf479a3fc18776ab90c776
```

The exact package is included in:

```text
release_artifacts/FoundationsCluster-0.0.6a.R19-HF11-SOURCE.zip
```

It is also expanded into:

```text
source/Cluster/
```

## Current compiled artifacts

```text
Manager JAR
source/Cluster/cluster-manager/build/FoundationsClusterManager.jar
SHA-256: a660cdc2c9a202dc77dfd112f0841fed80506704fedd758ae87c8f44a4c7b5a4

Node R13 JAR
source/Cluster/minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R13.jar
SHA-256: fedf7debff759588e5e0a75d2e9052b40e08c7b3205e341038ed0650f849ae8a

Gateway R19 JAR
source/Cluster/velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar
SHA-256: 21d4b834cc48a3aed09a5fa0be7d8b4e696e6b7c0a712f657593280ccc8a7b85
```

## Expected embedded versions

```text
ClusterVersion.VERSION          0.0.6a.R19
ClusterVersion.NODE_VERSION     0.0.6a.R13
ClusterVersion.PACKAGE_REVISION 0.0.6a.R19-HF11
Node mod_version                0.0.6a.R13
```

## Important note

The frozen v2.2 architecture documents in this handoff describe planned work and are not proof that those components exist in the HF11 source.
