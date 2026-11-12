# Propagating a catalog release

The release workflow publishes the BOM/catalog. This local workflow prepares its
adoption across the maintained Sava and GLAM Java consumers. An agent runs it after
a release; it is not a background scheduler or cross-repository CI trigger.

The registry is [consumers.properties](consumers.properties). Checkout paths are relative to
`--workspace`, which defaults to the parent of this repository. The registry lists
each pin, standalone build, and validation command. Dependency usage is inspected
from Gradle's resolved graphs rather than maintained as a second dependency map.
The helper runs directly from its Java source file. It uses only the JDK's
standard APIs; Gradle reads the published catalogs through its version-catalog API.

## Running it

Use JDK 25 or newer, Git, each checkout's Gradle wrapper and Java toolchains,
and the existing GitHub Packages credentials used by those builds. Recent catalog
releases may be GitHub Packages only; Maven Central metadata is not a release
availability check. Wait for successful publication before starting propagation.

```sh
# Inventory only: no Gradle execution or source edits.
java scripts/PropagateCatalog.java --list

# Replace 25.30.62 with the exact release just published.
# Preview resolves dependencies and writes logs, but changes no source pins.
java scripts/PropagateCatalog.java --version 25.30.62

# Prepare affected local changes and run the registered checks.
java scripts/PropagateCatalog.java --version 25.30.62 --apply

# Focus a retry or investigation; this does not establish fleet-wide completion.
java scripts/PropagateCatalog.java --version 25.30.62 \
  --consumer glam-sdk-java --apply
```

Read the relevant consumers' `AGENTS.md` before applying changes. Both modes run
trusted Gradle build code; preview can download artifacts and populate build
caches. It is not a sandbox for unknown repositories. The script neither fetches
nor changes Git history: synchronize checkouts through the normal local workflow
first, without overwriting existing work.

Each invocation writes a Markdown summary and per-command logs under
`build/propagation/<timestamp>/`. `--report-dir` can specify a different new
directory. Internal probe reports sit next to the logs for diagnostics; they are
not the user-facing handoff. The console prints progress and a summary. The
summary names each checkout's branch and commit, gives the reason for a blocked
or failed consumer, and says whether the run had finished when it was written.
A failed Gradle command's own reason and the probe's findings are quoted there.
Where they quote Gradle's failure text, addresses lose user information and
query strings and the credential forms the helper recognises are masked. That
masking is best effort (a plain lower-case word after a bare `Bearer` or
`Basic`, for one, is kept as prose), and the per-command logs keep Gradle's
output as written: read a report before passing it on.

## Selection and validation

For each enabled clean checkout, the helper reads `solanaBOMVersion`, resolves the
published old and target POM and catalog through that checkout's repositories,
and compares the target with that consumer's own baseline. A consumer several
releases behind therefore includes all intervening changes. The target version
is always explicit, and a newer consumer pin is never downgraded. A consumer
registered with a `branch` is blocked while its checkout is on another branch or
a detached HEAD, in preview too: a pin prepared on the wrong branch is not the
update the release needs.

The Gradle probe follows actual non-constraint dependency edges, including
transitive dependencies, across all subprojects' source-set compile, runtime and
annotation-processor classpaths. It also inspects other resolvable configurations
with declared dependencies and project buildscript classpaths. An unused BOM
constraint does not count as dependency use. Standalone benchmark and soak builds
are invoked separately where registered. Verification-category report inputs and
the legacy `default` configuration are excluded from the extra configuration
scan. Other configurations with no declared dependencies are not resolved either,
since a plugin may fill them only at resolution time; the probe report lists them
as skipped. Graphs that hold no published module at all block the consumer
instead of reading as unaffected. Settings-plugin private graphs and internal
included builds are not silently claimed as covered; add a separately consuming
internal build to `builds` when needed. Build-plugin versions such as `software.sava.build` have their own release
workflow and are not this BOM's downstream pins.

Gradle imports a published catalog through settings repositories only. The
catalogs are the same for every build of a checkout, so they are read once per
consumer, from a registered build that declares settings repositories (the root
build of a sava-build consumer). A standalone build that declares its
repositories in build scripts contributes its graphs and resolves the published
POM and catalog files through those repositories. A consumer with no build able
to read the catalogs is blocked. That includes a build whose settings
repositories are added by a `settingsEvaluated` callback of its own: the probe
decides on the import before such a callback runs, and reports the catalogs as
late. Register under `builds` only builds that apply
the BOM: one that does not cannot resolve it and blocks the consumer, so keep
such a build under validation alone.

A changed used module makes a consumer affected. Catalog aliases, bundles,
plugins, or explicit version aliases changing conservatively make all inspected
consumers affected, since those can change build behavior outside a runtime
graph. Imported/inherited BOM models the parser cannot interpret are blocked,
not guessed at. A resolution failure also blocks classification.

Apply mode rechecks the clean checkout and unchanged HEAD, edits only the BOM
pin, resolves the target graphs, and runs the registered validation. Preview, and
apply before its edit, resolve the current graphs plus the target POM and
catalog. The target graphs are first resolved after the edit, so an artifact that
is missing or untrusted only there shows as NEEDS REPAIR in apply, and a clean
preview does not prove that the target resolves.

Apply validates already-current consumers as well, so a pin committed without
passing validation cannot report success. A failed or interrupted apply leaves
its pin edit in place; a run that is terminated stops the Gradle command it was
running and starts no other. Rerunning apply for the same version continues from that
edit and validates it when it is the checkout's only difference from HEAD: one
unstaged line, the pin moved to the target. Any other local change, and every
preview, still needs a clean checkout. After a repair that touches other files,
commit the pin and the repair locally under the consumer's rules and rerun; the
consumer then reports CURRENT once validation passes. Consumers requiring cold-cache
checks get a private temporary Gradle home: only `org.gradle.java.*` settings are
copied, and package credentials are supplied as
`ORG_GRADLE_PROJECT_savaGithubPackages*` environment variables, both read from the
home Gradle reported for the run before it. No artifact cache is copied, and an
inherited `GRADLE_RO_DEP_CACHE` is dropped. Their registered checks also run in that
cold home. Every cold command names the home with `--gradle-user-home` as well as in
`GRADLE_USER_HOME`: a `gradle.user.home` system property, as `GRADLE_OPTS`,
`JAVA_OPTS` or `JAVA_TOOL_OPTIONS` can set one, outranks the variable and would
leave the run in the caller's warm cache, while the option outranks the property.
The probe reports the home Gradle used, and a cold probe that ran anywhere else
fails the validation. Gradle refuses the option twice, so a registered check or
`gradleArgs` must not pass it. A Gradle build that a registered check starts by
itself gets the variable but not the option, so an inherited `gradle.user.home`
property still decides its home, and nothing checks where it ran.

Sibling source composites, local `sava-build` overrides, mismatched BOM property
overrides, unavailable artifacts, and strict verification failures in the current
graphs or the target POM and catalog block the consumer before any edit. The
helper does not generate checksums or trust rules. If the new
release requires verification metadata changes, follow that consumer's documented
procedure, review the changes, and rerun its prescribed validation. A dirty
checkout stays blocked until its work has been reviewed and handled locally.

## Reading the result

| Result | Meaning |
|---|---|
| WOULD UPDATE | Preview found a relevant change; no pin was edited. |
| UPDATED | The local pin is changed, by this run or by an earlier one it continued from, and the registered validation passed. |
| CURRENT | The target was already pinned; apply validates it, preview does not. |
| NEWER | A newer pin was preserved; it was not validated against this target. |
| UNAFFECTED | No relevant change was found within the documented inspected graphs. |
| BLOCKED | A checkout or dependency inspection check failed before any pin edit; the detail gives the reason. |
| NEEDS REPAIR | Validation of the target failed: after the pin edit, which is retained for repair and review, or for a pin that was already at the target. |
| IN PROGRESS | Only in a report whose run did not finish: the pin edit and validation had started, or the validation of an edit the run continued from. |
| EXCLUDED | The registry deliberately excludes this repository, with a reason. |

Any blocked or repair result makes the command exit nonzero. Requesting an
excluded consumer explicitly also exits nonzero. Review the complete report,
including exclusions, before describing the release follow-up as complete.
Nothing commits, pushes, creates PRs, publishes libraries, or deploys services.
Give the maintainer the local diffs and validation results and wait for push
authorization.

For a Ravina release, first prepare its BOM constraint update. Publish the BOM
through the existing Release Please workflow when authorized, then run this
workflow. Updating the SDK's or service's BOM pin is distinct from publishing the
SDK or deploying the service; do not create a recursive release chain between
the catalog and the libraries it lists.

## Maintaining coverage

The public registry defines which public repositories participate in this workflow. Other
local checkouts, forks, certification/worktree duplicates, and the retired
`anchor-programs` tree are outside its scope. It is a maintained inventory, not
proof that every repository on GitHub has been discovered.

Keep private consumers in a separate registry outside this public checkout, using
the same properties format. The existing `--registry` option selects it for a
separate invocation; it replaces rather than merges with the public registry.
Run both registries when local agent context calls for private consumers, and
keep their reports outside the public checkout as well:

```sh
java scripts/PropagateCatalog.java --version 25.30.62 \
  --registry /path/to/private-consumers.properties \
  --workspace /path/to/workspace \
  --report-dir /path/to/private-reports/new-run
```

Add `--apply` to prepare and validate affected updates. Machine-level agent
instructions should identify optional private checkouts and run this follow-up
when they exist. Do not add their names, paths, or validation commands here.

When adding a consumer, register its canonical checkout and branch, exact pin,
all separately consuming builds, published-mode Gradle arguments if needed, and
commands from its
own agent/verification guidance. Keep new consumers and changed validation rules
in the same local review. Find candidates with:

```sh
rg -l 'solanaBOMVersion|software.sava:solana-version-catalog' /path/to/workspace \
  -g '*.properties' -g '*.gradle' -g '*.gradle.kts' -g '*.toml' -g 'pom.xml'
```

The current helper intentionally supports the shared `gradle/sava.properties`
pin convention. Register a new pin format only after adding an updater and tests;
do not silently omit consumers with literal Gradle imports or Maven POM imports.

Registry entries use `consumer.<name>.<field>` keys. `consumers` and `builds` are
comma-separated lists. `path` is the checkout relative to the workspace, `pin` the
properties file that holds `solanaBOMVersion` (default `gradle/sava.properties`),
and `branch`, when set, the branch a prepared update belongs on. `validation.0`,
`validation.1`, and so on contain whitespace-separated argument lists beginning
with `./gradlew`. These arguments
are passed directly to Gradle, without a shell; shell quoting and expansion are
not supported. `gradleArgs` supplies shared inspection/validation arguments, and
`coldResolve=true` adds the cold-cache checks. `enabled=false` excludes a consumer
and requires `notes` giving the reason; `notes` is otherwise free text. Unknown
keys, and keys of a consumer missing from `consumers`, are rejected, so that a
misspelled field cannot silently drop a check. Keep command arguments that need
embedded whitespace out of this format until an explicit encoding is added.

The test runner also runs directly from source:

```sh
# Fast temporary-checkout tests, including write and validation failure cases.
java scripts/PropagationTests.java

# Add offline integration tests using an installed Gradle matching the wrapper.
java scripts/PropagationTests.java --gradle /path/to/gradle-9.9.1/bin/gradle
```

The integration tests build local Maven fixtures and temporary consumers. They
cover real Gradle resolution, transitive dependencies, subprojects under parallel
and configure-on-demand settings, unused constraints, catalog changes outside the
libraries, strict checksum failures, and standalone builds shaped like the
registered jmh and soak builds, without changing maintained checkouts.

## Agent context

Global agent instructions should point to this workflow and separately identify
any machine-local consumer registry. A portable public rule is:

> After publishing a Sava/GLAM dependency managed by the Solana BOM/catalog,
> prepare its corresponding catalog update. After publishing the catalog, read
> the catalog repository's `downstream/README.md` and run its propagation workflow
> for all enabled public consumers and applicable machine-local consumers. Prepare and validate
> affected local updates, including transitive consumers; preserve unrelated work
> and newer pins. Report blocked and excluded repositories explicitly. Present
> local diffs and results before requesting authorization to push. Publishing a
> downstream library or deploying a service requires separate authorization; do
> not recursively release consumers or create development PRs.

Keep checkout paths and private consumer details in the machine's global context,
and pass its workspace root to the helper. Global guidance only triggers work during an agent
session; unattended execution would require a separately configured automation.
