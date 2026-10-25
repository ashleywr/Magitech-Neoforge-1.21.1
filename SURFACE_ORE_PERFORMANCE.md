# Surface ore performance fork

Based on upstream Magitech 2.4.4, commit
`339cafc16f6ab16542269acb5ba4f8279cfee0e9`. The fork version is
`2.4.4-ashley.1`.

The surface crystal feature keeps its placement rules, scan order and every
random draw. Ordinary worlds avoid repeated height/random getter calls and
redundant face reads. With the verified SeedGen 0.9.1 virtual terrain, the feature
examines the sparse placed-block map for possible ore targets rather than
querying every voxel. SeedGen's implicit terrain cannot contain those ores.

The optional path checks the exact bytecode of the relevant SeedGen classes.
It falls back to the full scan for other versions, unsupported terrain, large
sparse maps, implicit-terrain targets or replacements that can create targets.
SeedGen is not a required dependency. Floating terrain uses the full scan.

## Verification

Run `./gradlew surfaceOreRegression`. To exercise the actual SeedGen proxy too,
run `./gradlew surfaceOreRegression -PseedgenJar=/path/to/SeedGen-0.9.1.jar`.
The optional JAR remains local and is not redistributed.

The test compares against the original upstream implementation across 67
scenarios, including wet caves, negative chunks, different build heights,
directional states and both Minecraft random sources. It checks ordered writes,
final states and subsequent random values. The optional SeedGen suite adds 16
state/random comparisons and three fallback checks.

An alternating six-round CPU benchmark of 256 target-free SeedGen chunks per
round measured median 1,023 ms upstream versus 172 ms with this change, excluding
fixture construction. The real SeedGen proxy and terrain were used with a mock
height sampler. Target-free chunks avoid all 256 terrain-height queries per
feature. These are feature measurements, not overall world-generation rates.

No terrain quality or DH distance setting is changed. Existing worlds remain
usable; the optimization applies when generation next invokes the feature.
