# Manager security dependency

`bcprov.jar` is required by both the Manager build and its runtime classpath.
It supplies Bouncy Castle Argon2id; it is deliberately tracked as a runtime
dependency so downloading GitHub source does not break the existing launchers.

This is the exact Bouncy Castle 1.80.0 JAR supplied with the HF11 handoff,
including its Debian build metadata. It is not a substituted or newly downloaded
Maven artifact. SHA-256:

`caaf68a5fdf4a8e039b7e2623aa8370038614b14fbcd20ab4835db2f1c7e823d`

The embedded license is also reproduced in `BOUNCY_CASTLE_LICENSE.txt`.
The security implementation and password hash format are unchanged.
