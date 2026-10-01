# Windows source-build path correction

The uploaded implementation sources are complete. A source checkout compiles
the binaries locally; it is distinct from the prebuilt HF11 release archive.
The earlier missing Bouncy Castle dependency was restored separately.

The simulator build wrote quoted absolute Windows paths into a javac argument
file without normalizing backslashes. Inside those quotes javac consumes escape
sequences, including `\n` and `\f`, so `node-simulator` and `foundations` can become
newlines/form feeds and the remaining backslashes disappear. This produces the
reported Invalid filename error despite the source file being present.

`build_simulator.bat` now delegates to a PowerShell builder following the Manager
builder's established convention: quoted forward-slash paths and UTF-8 without
a BOM. It uses the discovered JDK 21, checks compiler and packaging exit codes,
and installs the simulator JAR only after packaging succeeds. The reference-test
batch build had the same raw-path issue and now delegates to a matching helper.

Validation: the javac argument-file parser reproduced corruption of the exact
reported Windows path; normalized and space-containing paths were preserved.
The real simulator source compiled using the normalized list and available
Java 17 compiler API. Windows PowerShell and Java 21 are not available in this
validation environment, so a complete Windows/NeoForge build is not claimed.
The scripts retain `--release 21` for the actual Windows build.

Pull updated main, then rerun `BUILD_ALL_VISIBLE.bat` to complete the source
build. Existing worlds, credentials, configuration and runtime versions are
not changed by this correction.
