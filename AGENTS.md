# Catalog release propagation

After a BOM/catalog release, follow [downstream/README.md](downstream/README.md).
Publication alone does not finish the local release follow-up: account for every
enabled consumer in [downstream/consumers.properties](downstream/consumers.properties), prepare
affected BOM pin updates, and run each consumer's required checks. Record blocked
and excluded repositories explicitly; never claim complete propagation from a
filtered run or a failed dependency inspection.

This is a public repository: its consumer registry and documentation must contain
only public consumers. Keep private consumer identities and validation commands
in a machine-local registry outside this checkout. Follow global agent context
for any additional local consumers, using the helper's `--registry` option.

Read each affected repository's agent guidance before applying its update.
Compare its own pinned release with the target, including transitive dependencies
and standalone builds. Preserve local changes and newer pins. Use published
artifacts, strict dependency verification, and no sibling source substitutions.
Verification-policy changes require the consumer's existing review procedure;
the helper must not invent trust rules or weaken verification to make a run pass.

Present local diffs and validation results before pushing. Do not open development
PRs, enable auto-merge, or infer permission to publish another library or deploy a
service. Catalog propagation does not recursively release its consumers.

## Java style

- Use `final` for parameters, fields, and local variables that are not reassigned.
- Prefer `final var` for non-primitive locals; keep explicit types where Java
  requires them or type inference would change the intended type.
- Use braces for every control-flow body, including single-statement branches
  and loops. Put nested `for` and `if` statements on separate lines.
- Use one statement per line and consistent two-space block indentation.

## Validation

For changes to the propagation tooling, run:

```sh
java scripts/PropagationTests.java
java scripts/PropagateCatalog.java --list
```

The helper requires JDK 25 or newer and no third-party dependencies. Its Gradle probe must also be exercised against
the repository's pinned Gradle with local Maven fixtures when changed; test direct
and transitive use, unused BOM constraints, and resolution/substitution failures.

```sh
java scripts/PropagationTests.java --gradle /path/to/gradle-9.9.1/bin/gradle
```

Use an already-installed distribution matching the wrapper; the integration
fixtures run offline, use temporary repositories, and change no real consumers.
Shape a fixture like the builds it stands for: the registered jmh and soak builds
declare dependency repositories in their build scripts and none in settings (a
jmh build's settings carry plugin repositories only). After a change
to the probe, also run a preview (no `--apply`) across the registered consumers;
it edits no pin and shows what the fixtures cannot.
