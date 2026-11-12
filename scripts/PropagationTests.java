import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free black-box tests. Add --gradle /existing/bin/gradle for offline integration.
 */
class PropagationTests {

  static final String OLD = "25.1.0";
  static final String TARGET = "25.1.1";
  static final Path SOURCE = Path.of(System.getProperty("jdk.launcher.sourcefile", "scripts/PropagationTests.java")).toAbsolutePath();
  static final Path HELPER = SOURCE.resolveSibling("PropagateCatalog.java");
  static final String JAVA = Path.of(System.getProperty("java.home"), "bin/java").toString();
  static Path gradle;
  static int passed;
  static int failed;
  interface Check {

    void run() throws Exception;
  }

  record Result(int code, String summary, String diagnostics) {
  }

  public static void main(final String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--fake-gradle")) {
      fakeGradle(args);
      return;
    }
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--gradle") && i + 1 < args.length) {
        gradle = Path.of(args[++i]).toAbsolutePath();
      } else {
        throw new IllegalArgumentException("Usage: java scripts/PropagationTests.java [--gradle /existing/bin/gradle]");
      }
    }
    if (gradle != null && !Files.isExecutable(gradle)) {
      throw new IllegalArgumentException("Gradle executable missing: " + gradle);
    }
    test("preview and apply preserve exact pin formatting", () -> {
      try (final var f = new Fixture(false)) {
        final var original = Files.readString(f.pin);
        f.commit("input");
        final var preview = f.run("preview", false, TARGET);
        expect(preview, 0, "WOULD UPDATE");
        contains(preview.summary, "fixture:leaf");
        equal(original, Files.readString(f.pin));
        equal("", f.git("status", "--porcelain"));
        final var applied = f.run("apply", true, TARGET);
        expect(applied, 0, "UPDATED");
        contains(applied.summary, "Consumers in this report: 1 of 1.\n");
        equal(original.replace("solanaBOMVersion \t= " + OLD, "solanaBOMVersion \t= " + TARGET), Files.readString(f.pin));
        equal("gradle/sava.properties", f.git("diff", "--name-only"));
        require(Files.exists(f.repo.resolve("build/validation-ran")), "registered validation did not run");
      }
    });
    test("unused constraints do not affect actual graph", () -> {
      try (final var f = new Fixture(false)) {
        f.boms("1");
        f.commit();
        expect(f.run("unused", true, TARGET), 0, "UNAFFECTED");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("dirty checkout preserved", () -> {
      try (final var f = new Fixture(false)) {
        f.commit();
        Files.writeString(f.repo.resolve("keep.txt"), "keep");
        expect(f.run("dirty", true, TARGET), 1, "BLOCKED");
        contains(Files.readString(f.pin), OLD);
        equal("keep", Files.readString(f.repo.resolve("keep.txt")));
      }
    });
    test("newer pin never downgraded", () -> {
      try (final var f = new Fixture(false)) {
        f.commit();
        expect(f.run("newer", true, "25.0.9"), 0, "NEWER");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("failed validation retains target for repair", () -> {
      try (final var f = new Fixture(false)) {
        f.config.setProperty("validationFail", "true");
        f.saveConfig();
        f.commit();
        final var result = f.run("failed", true, TARGET);
        expect(result, 1, "NEEDS REPAIR");
        contains(Files.readString(f.pin), "solanaBOMVersion \t= " + TARGET);
        // Gradle's own reason, on one line and without the credentials it quoted.
        contains(result.summary, "Command exited 1: Execution failed for task ':check'. boom https://repo.example/x?redacted"
            + " Authorization: [redacted] {\"token\":[redacted]} sent Bearer [redacted] over Basic authentication."
            + " Basic [redacted] or Bearer [redacted] with password=[redacted] and {\\\"token\\\":[redacted]}"
            + " and password = [redacted] or password=[redacted] then {\"token\":[redacted]} done; inspect ");
        final var console = Files.readString(f.root.resolve("failed.log"));
        contains(console, "NEEDS REPAIR [main @ ");
        for (final var secret : List.of("in-address", "in-query", "in-header", "in-json", "after-scheme",
            "dXNlckxpbmVhcg", "in-quotes", "in-tail", "in-escaped", "in spaces", "in-apostrophe")) {
          require(!result.summary.contains(secret) && !console.contains(secret),
              secret + " reached the summary or the console:\n" + result.summary + console);
        }
      }
    });
    test("already-current apply revalidates", () -> {
      try (final var f = new Fixture(false)) {
        f.commit();
        expect(f.run("current", true, OLD), 0, "CURRENT");
        require(Files.exists(f.repo.resolve("build/validation-ran")), "current pin was not validated");
      }
    });
    test("missing and duplicate pins rejected", () -> {
      for (final var pin : List.of(
          "other=1\n",
          "solanaBOMVersion=" + OLD + "\nsolanaBOMVersion=" + TARGET + "\n",
          // Java properties accept an indented key and keep the last assignment.
          "solanaBOMVersion=" + OLD + "\n  solanaBOMVersion=" + TARGET + "\n",
          // The same value again: the build would go on reading it after the first line is edited.
          "solanaBOMVersion=" + OLD + "\nsolanaBOMVersion:" + OLD + "\n",
          "solanaBOMVersion: " + OLD + "\n")) {
        try (final var f = new Fixture(false)) {
          Files.writeString(f.pin, pin);
          f.commit();
          expect(f.run("bad-pin", true, TARGET), 1, "BLOCKED");
          equal(pin, Files.readString(f.pin));
        }
      }
    });
    test("inherited and malformed BOMs rejected", () -> {
      for (final var pom : List.of("<project>", Fixture.pom("software.sava", "solana-version-catalog", OLD, "<parent><groupId>x</groupId><artifactId>x</artifactId><version>1</version></parent>", "pom"))) {
        try (final var f = new Fixture(false)) {
          Files.writeString(f.pom(OLD), pom);
          f.commit();
          expect(f.run("bad-pom", true, TARGET), 1, "BLOCKED");
        }
      }
    });
    test("catalog-only library change affects used module", () -> {
      try (final var f = new Fixture(false)) {
        f.boms("1");
        f.config.setProperty("catalogLeafTarget", "2");
        f.saveConfig();
        f.commit();
        expect(f.run("catalog-only", false, TARGET), 0, "WOULD UPDATE");
      }
    });
    test("public catalog structure changes update conservatively", () -> {
      try (final var f = new Fixture(false)) {
        f.boms("1");
        f.config.setProperty("shapeChange", "true");
        f.saveConfig();
        f.commit();
        expect(f.run("shape", false, TARGET), 0, "WOULD UPDATE");
      }
    });
    test("catalogs are read once per consumer, from a build that could import them", () -> {
      // fixture:tool is used by the nested build alone, which reads no catalog, and changes in the catalog alone.
      try (final var f = new Fixture(false)) {
        f.boms("1");
        f.nested("skipped", "nested,.");
        f.commit();
        final var result = f.run("standalone", false, TARGET);
        expect(result, 0, "WOULD UPDATE");
        contains(result.summary, "| WOULD UPDATE | catalog version for fixture:tool: 1 → 2 |");
        equal("skipped", load(f.root.resolve("standalone/fixture/baseline-0.properties")).getProperty("catalogs"));
      }
      try (final var f = new Fixture(false)) {
        f.boms("1");
        f.nested("differs", ".,nested");
        f.commit();
        final var result = f.run("disagreeing", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "Registered builds read different published catalogs");
        contains(Files.readString(f.pin), OLD);
      }
    });
    test("concurrent tracked edits preserved during inspection and validation", () -> {
      for (final var phase : List.of("baseline", "validation")) {
        try (final var f = new Fixture(false)) {
          f.config.setProperty("mutate", phase);
          f.saveConfig();
          f.commit();
          expect(f.run("concurrent", phase.equals("validation"), TARGET), 1, phase.equals("validation") ? "NEEDS REPAIR" : "BLOCKED");
          equal("concurrent edit\n", Files.readString(f.repo.resolve("tracked.txt")));
          contains(Files.readString(f.pin), "solanaBOMVersion \t= " + (phase.equals("validation") ? TARGET : OLD));
        }
      }
    });
    test("cold validation isolates settings and passes credentials in environment", () -> {
      try (final var f = new Fixture(false)) {
        final var home = f.root.resolve("gradle-home");
        Files.createDirectories(home);
        final var original = new Properties();
        original.setProperty("org.gradle.java.installations.paths", "/fixture/jdk-\u00e9");
        original.setProperty("org.gradle.java.installations.auto-download", "false");
        original.setProperty("savaGithubPackagesUsername", "fixture-user");
        original.setProperty("savaGithubPackagesPassword", "fixture-secret");
        original.setProperty("savaBuildLocalRepo", "/fixture/local");
        original.setProperty("org.gradle.jvmargs", "-Dlocal=true");
        store(original, home.resolve("gradle.properties"));
        f.config.setProperty("checkCold", "true");
        f.saveConfig();
        f.registry.setProperty("consumer.fixture.coldResolve", "true");
        f.saveRegistry();
        // Gradle keeps the last of a repeated key, so the cold step must accept one too.
        Files.writeString(home.resolve("gradle.properties"), "org.gradle.jvmargs=-Dlocal=true\n", StandardOpenOption.APPEND);
        f.commit();
        expect(f.run("cold", true, TARGET), 0, "UPDATED");
        equal(original, load(home.resolve("gradle.properties")));
        require(Files.exists(f.root.resolve("cold-probe")), "cold probe did not run");
        require(Files.exists(f.root.resolve("cold-validation")), "cold validation did not run");
      }
    });
    test("unknown consumer rejected", () -> {
      try (final var f = new Fixture(false)) {
        f.commit();
        final var result = f.run("unknown", false, TARGET, "--consumer", "missing");
        equal(2, result.code);
        contains(result.diagnostics, "Unknown consumer");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("registry mistakes are rejected", () -> {
      final var mistakes = new LinkedHashMap<Map<String, String>, String>();
      mistakes.put(Map.of("consumer.fixture.coldresolve", "true"), "Unknown registry key: consumer.fixture.coldresolve");
      mistakes.put(Map.of("consumer.other.path", "elsewhere"), "Unknown registry key: consumer.other.path");
      mistakes.put(Map.of("consumer.fixture.build", ".,nested", "consumer.fixture.builds", "nested"),
          "Unknown registry key: consumer.fixture.build");
      mistakes.put(Map.of("consumer.fixture.validation.2", "./gradlew check"), "validation indexes must start at 0 with no gaps");
      mistakes.put(Map.of("consumer.fixture.validation.1", "gradle check"), "validation must contain literal ./gradlew");
      mistakes.put(Map.of("consumer.fixture.enabled", "false"), "a disabled consumer needs notes");
      mistakes.put(Map.of("consumer.fixture.builds", "nested"), "builds must include the root build");
      mistakes.put(Map.of("consumer.fixture.branch", " "), "branch must not be blank");
      mistakes.put(Map.of("consumers", "fixture,twin", "consumer.twin.path", "./consumer", "consumer.twin.validation.0", "./gradlew check"),
          "Duplicate consumer path");
      for (final var mistake : mistakes.entrySet()) {
        try (final var f = new Fixture(false)) {
          mistake.getKey().forEach(f.registry::setProperty);
          f.saveRegistry();
          f.commit();
          final var result = f.run("registry", true, TARGET);
          equal(2, result.code);
          contains(result.diagnostics, mistake.getValue());
          contains(Files.readString(f.pin), OLD);
        }
      }
      try (final var f = new Fixture(false)) {
        Files.writeString(f.root.resolve("consumers.properties"), "version=1\n", StandardOpenOption.APPEND);
        f.commit();
        final var result = f.run("registry", true, TARGET);
        equal(2, result.code);
        contains(result.diagnostics, "Duplicate property key: version");
      }
    });
    test("excluded consumers carry their reason and a report directory is never reused", () -> {
      try (final var f = new Fixture(false)) {
        f.registry.setProperty("consumer.fixture.enabled", "false");
        f.registry.setProperty("consumer.fixture.notes", "Retired fixture");
        f.saveRegistry();
        f.commit();
        final var all = f.run("all", true, TARGET);
        expect(all, 0, "EXCLUDED");
        contains(all.summary, "Retired fixture");
        expect(f.run("named", true, TARGET, "--consumer", "fixture"), 1, "EXCLUDED");
        final var reused = f.run("all", true, TARGET);
        equal(2, reused.code);
        contains(reused.diagnostics, "Already exists");
        contains(Files.readString(f.pin), OLD);
      }
    });
    test("probe findings reach the summary", () -> {
      try (final var f = new Fixture(false)) {
        // Relayed as the probe wrote them: it masks what it quotes, and its coordinates must stay readable.
        f.config.setProperty("probeErrors", "savaBuildLocalRepo is set: published plugin resolution cannot be certified\n"
            + ":runtimeClasspath: unresolved io.jsonwebtoken:jjwt-api:0.12.6");
        f.saveConfig();
        f.commit();
        final var result = f.run("findings", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "| BLOCKED | :runtimeClasspath: unresolved io.jsonwebtoken:jjwt-api:0.12.6"
            + "; savaBuildLocalRepo is set: published plugin resolution cannot be certified; inspect ");
        contains(Files.readString(f.pin), OLD);
      }
    });
    test("graphs without a published module are not called unaffected", () -> {
      try (final var f = new Fixture(false)) {
        f.config.setProperty("noModules", "true");
        f.saveConfig();
        f.commit();
        final var result = f.run("empty", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "No published module in any inspected graph");
        contains(Files.readString(f.pin), OLD);
      }
    });
    test("summary records the checkout and a registered branch is enforced", () -> {
      try (final var f = new Fixture(false)) {
        f.commit();
        // A tag named like the branch makes its short name ambiguous to git; the checkout is on main all the same.
        f.git("tag", "main");
        final var head = f.git("rev-parse", "HEAD").substring(0, 10);
        final var preview = f.run("unregistered", false, TARGET);
        expect(preview, 0, "WOULD UPDATE");
        contains(preview.summary, "| fixture | main @ " + head + " | " + OLD + " | WOULD UPDATE |");
        f.registry.setProperty("consumer.fixture.branch", "release");
        f.saveRegistry();
        // A preview enforces the branch too.
        final var other = f.run("other-branch", false, TARGET);
        expect(other, 1, "BLOCKED");
        contains(other.summary, "Checkout is on main; the registry expects release");
        f.registry.setProperty("consumer.fixture.branch", "main");
        f.saveRegistry();
        f.git("checkout", "-q", "--detach");
        final var detached = f.run("detached", true, TARGET);
        expect(detached, 1, "BLOCKED");
        contains(detached.summary, "| detached @ " + head + " |");
        contains(detached.summary, "Checkout is on a detached HEAD; the registry expects main");
        contains(Files.readString(f.pin), OLD);
        f.git("checkout", "-q", "main");
        expect(f.run("registered-branch", true, TARGET), 0, "UPDATED");
      }
    });
    test("an interrupted apply would leave a report of its pin edit", () -> {
      try (final var f = new Fixture(false)) {
        f.config.setProperty("snapshotSummary", "apply");
        f.saveConfig();
        f.commit();
        expect(f.run("apply", true, TARGET), 0, "UPDATED");
        final var during = Files.readString(f.root.resolve("summary-during-validation.md"));
        contains(during, "| " + OLD + " | IN PROGRESS | Pin edit and validation had started");
        contains(during, "The run had not finished when this report was written.");
      }
    });
    test("a terminated apply stops its Gradle child and leaves the report of its pin edit", () -> {
      try (final var f = new Fixture(false)) {
        f.config.setProperty("validationHang", "true");
        f.saveConfig();
        f.commit();
        final var marker = f.root.resolve("validation.pid");
        final var helper = f.start("terminated", true, TARGET);
        final var children = new ArrayList<ProcessHandle>();
        try {
          for (int i = 0; i < 600 && !Files.exists(marker) && helper.isAlive(); i++) {
            Thread.sleep(100);
          }
          require(Files.exists(marker), "validation never started:\n" + Files.readString(f.root.resolve("terminated.log")));
          ProcessHandle.of(Long.parseLong(Files.readString(marker).strip())).ifPresent(children::add);
          require(!children.isEmpty() && children.getFirst().isAlive(), "validation child is not running");
          helper.destroy();
          require(helper.waitFor(30, TimeUnit.SECONDS), "helper ignored the termination request");
          for (int i = 0; i < 100 && children.getFirst().isAlive(); i++) {
            Thread.sleep(100);
          }
          require(!children.getFirst().isAlive(), "the Gradle child outlived the helper");
          // The child the helper stopped is not a failed check: the report keeps the edit as in progress.
          final var summary = Files.readString(f.root.resolve("terminated/summary.md"));
          contains(summary, "| " + OLD + " | IN PROGRESS | Pin edit and validation had started");
          contains(summary, "The run had not finished when this report was written.");
          require(!summary.contains("Command exited"), "the stopped child was reported as a failed check:\n" + summary);
          contains(Files.readString(f.root.resolve("terminated.log")), "Error: Stopped during validation-0.log");
          contains(Files.readString(f.pin), "solanaBOMVersion \t= " + TARGET);
        } finally {
          helper.descendants().forEach(ProcessHandle::destroyForcibly);
          helper.destroyForcibly();
          children.forEach(ProcessHandle::destroyForcibly);
        }
      }
    });
    test("a termination during a git check is a stop, not a result", () -> {
      // The status check after validation either outlasts the termination and then succeeds, or is ended by a
      // signal just before the helper learns of its own, as when a terminal interrupts the whole process group.
      final var endings = Map.of(
          "outlasts", "sleep 3",
          "signalled", "(sleep 0.2; kill -TERM $PPID) > /dev/null 2>&1 &\n    kill -KILL $$");
      for (final var ending : endings.entrySet()) {
        try (final var f = new Fixture(false)) {
          f.config.setProperty("slowGit", "true");
          f.saveConfig();
          f.commit();
          final var marker = f.root.resolve("git.pid");
          final var wrapper = Files.createDirectories(f.root.resolve("bin")).resolve("git");
          Files.writeString(wrapper, "#!/bin/sh\nif [ -f " + quote(f.root.resolve("git.slow").toString()) + " ]; then\n"
              + "  case \" $* \" in *\" status \"*)\n    echo $$ > " + quote(marker + ".tmp") + " && mv " + quote(marker + ".tmp") + " " + quote(marker.toString())
              + "\n    " + ending.getValue() + ";;\n  esac\nfi\nexec " + quote(executable("git").toString()) + " \"$@\"\n");
          wrapper.toFile().setExecutable(true, true);
          final var helper = f.start("stopped", true, TARGET);
          try {
            for (int i = 0; i < 600 && !Files.exists(marker) && helper.isAlive(); i++) {
              Thread.sleep(100);
            }
            require(Files.exists(marker), ending.getKey() + ": the git check never started:\n" + Files.readString(f.root.resolve("stopped.log")));
            if (ending.getKey().equals("outlasts")) {
              helper.destroy();
            }
            require(helper.waitFor(30, TimeUnit.SECONDS), ending.getKey() + ": helper ignored the termination request");
            contains(Files.readString(f.root.resolve("stopped.log")), "Error: Stopped during git status");
            final var summary = Files.readString(f.root.resolve("stopped/summary.md"));
            contains(summary, "| " + OLD + " | IN PROGRESS | Pin edit and validation had started");
            contains(summary, "The run had not finished when this report was written.");
            contains(Files.readString(f.pin), "solanaBOMVersion \t= " + TARGET);
          } finally {
            helper.descendants().forEach(ProcessHandle::destroyForcibly);
            helper.destroyForcibly();
          }
        }
      }
    });
    test("a rerun continues from the pin edit a failed apply left in place", () -> {
      for (final var autocrlf : List.of("false", "input")) {
        try (final var f = new Fixture(false)) {
          f.config.setProperty("validationFail", "true");
          f.saveConfig();
          f.commit(autocrlf);
          expect(f.run("failed", true, TARGET), 1, "NEEDS REPAIR");
          final var edited = Files.readString(f.pin);
          // A preview never continues from an edit.
          expect(f.run("preview", false, TARGET), 1, "BLOCKED");
          // Nor does an apply to some other version.
          expect(f.run("other-target", true, "25.1.2"), 1, "BLOCKED");
          equal(edited, Files.readString(f.pin));
          final var again = f.run("failed-again", true, TARGET);
          expect(again, 1, "NEEDS REPAIR");
          contains(again.summary, "local pin edit retained");
          f.config.setProperty("validationFail", "false");
          f.config.setProperty("snapshotSummary", "retry");
          f.saveConfig();
          final var retry = f.run("retry", true, TARGET);
          expect(retry, 0, "UPDATED");
          contains(retry.summary, "| " + OLD + " | UPDATED | Validated the pin edit an earlier run left in place");
          contains(Files.readString(f.root.resolve("summary-during-validation.md")),
              "| " + OLD + " | IN PROGRESS | Validation of the pin edit an earlier run left in place had started");
          equal(edited, Files.readString(f.pin));
          equal("gradle/sava.properties", f.git("diff", "--name-only"));
        }
      }
    });
    test("only the helper's own pin edit is continued", () -> {
      final var bumped = "solanaBOMVersion \t= " + TARGET;
      for (final var state : List.of("second dirty file", "another line of the pin file", "staged", "a version other than the target", "file mode",
          "a downgrade of the committed pin")) {
        try (final var f = new Fixture(false)) {
          final var committed = state.equals("a downgrade of the committed pin") ? "25.1.2" : OLD;
          Files.writeString(f.pin, Files.readString(f.pin).replace("solanaBOMVersion \t= " + OLD, "solanaBOMVersion \t= " + committed));
          f.commit();
          var pin = Files.readString(f.pin).replace("solanaBOMVersion \t= " + committed, bumped);
          switch (state) {
            case "second dirty file" -> Files.writeString(f.repo.resolve("tracked.txt"), "someone's work\n");
            case "another line of the pin file" -> pin = pin.replace("otherVersion=" + OLD, "otherVersion=" + TARGET);
            case "a version other than the target" -> pin = pin.replace(bumped, "solanaBOMVersion \t= 25.1.2");
            default -> {
            }
          }
          Files.writeString(f.pin, pin);
          if (state.equals("staged")) {
            f.git("add", "gradle/sava.properties");
          }
          if (state.equals("file mode")) {
            f.pin.toFile().setExecutable(true, true);
          }
          final var result = f.run("not-ours", true, TARGET);
          require(result.code == 1 && result.summary.contains("BLOCKED") && result.summary.contains("Checkout has local changes"),
              state + " was continued:\n" + result.summary);
          equal(pin, Files.readString(f.pin));
          require(!Files.exists(f.repo.resolve("build/validation-ran")), state + ": validation ran");
        }
      }
    });
    test("failed validation of an already-current pin needs repair", () -> {
      try (final var f = new Fixture(false)) {
        f.config.setProperty("validationFail", "true");
        f.saveConfig();
        f.commit();
        final var result = f.run("current-failed", true, OLD);
        expect(result, 1, "NEEDS REPAIR");
        contains(result.summary, "the pin was already at the target and this run edited nothing");
        equal("", f.git("status", "--porcelain"));
      }
    });
    if (gradle != null) {
      integration();
    }
    System.out.printf("%d passed, %d failed%n", passed, failed);
    if (failed != 0) {
      System.exit(1);
    }
  }

  static void integration() throws Exception {
    test("Gradle: transitive preview/apply with strict checksums", () -> {
      try (final var f = new Fixture(true)) {
        f.commit();
        expect(f.run("preview", false, TARGET), 0, "WOULD UPDATE");
        equal("", f.git("status", "--porcelain"));
        final var result = f.run("apply", true, TARGET);
        expect(result, 0, "UPDATED");
        contains(result.summary, "fixture:leaf");
        equal("gradle/sava.properties", f.git("diff", "--name-only"));
        contains(Files.readString(f.root.resolve("apply/fixture/validation-0.log")), "BUILD SUCCESSFUL");
      }
    });
    test("Gradle: unused constraint ignored", () -> {
      try (final var f = new Fixture(true)) {
        f.boms("1");
        f.commit();
        expect(f.run("unused", true, TARGET), 0, "UNAFFECTED");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: missing artifact blocks", () -> {
      try (final var f = new Fixture(true)) {
        Files.delete(f.maven.resolve("fixture/leaf/1/leaf-1.jar"));
        f.commit();
        expect(f.run("missing", true, TARGET), 1, "BLOCKED");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: checksum mismatch blocks", () -> {
      try (final var f = new Fixture(true)) {
        Files.writeString(f.maven.resolve("fixture/leaf/1/leaf-1.jar"), "tampered", StandardOpenOption.APPEND);
        f.commit();
        expect(f.run("checksum", true, TARGET), 1, "BLOCKED");
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: external sibling composite blocks", () -> {
      try (final var f = new Fixture(true)) {
        final var sibling = f.root.resolve("replacement");
        Files.createDirectories(sibling);
        Files.writeString(sibling.resolve("settings.gradle"), "rootProject.name='leaf'\n");
        Files.writeString(sibling.resolve("build.gradle"), "plugins { id 'java-library' }\ngroup='fixture'\nversion='1'\n");
        Files.writeString(f.repo.resolve("settings.gradle"), "includeBuild('../replacement')\n", StandardOpenOption.APPEND);
        f.commit();
        final var result = f.run("sibling", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "External included build");
        equal("", f.git("status", "--porcelain"));
      }
    });
    // The registered jmh and soak builds declare repositories in their build scripts only:
    // a soak build's settings name the build and include the root, and a jmh build's add
    // plugin repositories. Neither can resolve a settings-level catalog import.
    final var nestedSettings = Map.of(
        "soak", "rootProject.name='nested'\nincludeBuild('..')\n",
        "jmh", "pluginManagement { repositories { maven { url=uri('../../maven') } } }\nrootProject.name='nested'\nincludeBuild('..')\n");
    for (final var shape : List.of("soak", "jmh")) {
      test("Gradle: nested " + shape + "-shaped build traversed without compilation", () -> {
        try (final var f = new Fixture(true)) {
          final var nested = f.repo.resolve("nested");
          Files.createDirectories(nested.resolve("gradle"));
          Files.writeString(nested.resolve("settings.gradle"), nestedSettings.get(shape));
          Files.writeString(nested.resolve("build.gradle"), "plugins { id 'java-library' }\nrepositories { maven { url=uri('../../maven') } }\ndependencies { implementation 'fixture:offline-consumer:1' }\n");
          Files.copy(f.repo.resolve("gradle/verification-metadata.xml"), nested.resolve("gradle/verification-metadata.xml"));
          Files.writeString(f.repo.resolve("build.gradle"), "group='fixture'\nversion='1'\ntasks.named('compileJava') { doFirst { throw new GradleException('must not compile') } }\n", StandardOpenOption.APPEND);
          final var source = f.repo.resolve("src/main/java/Fixture.java");
          Files.createDirectories(source.getParent());
          Files.writeString(source, "public class Fixture {}\n");
          f.registry.setProperty("consumer.fixture.builds", ".,nested");
          f.saveRegistry();
          f.commit();
          final var result = f.run("nested", false, TARGET);
          expect(result, 0, "WOULD UPDATE");
          final var report = load(f.root.resolve("nested/fixture/baseline-1.properties"));
          contains(report.getProperty("modules", ""), "fixture:leaf");
          require(!Files.exists(f.repo.resolve("build/classes")), "preview compiled project output");
          equal("", f.git("status", "--porcelain"));
        }
      });
    }
    test("Gradle: catalogs unreadable without any settings repositories", () -> {
      try (final var f = new Fixture(true)) {
        Files.writeString(f.repo.resolve("settings.gradle"), "rootProject.name='offline-consumer'\n");
        f.commit();
        final var result = f.run("no-settings-repositories", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "No registered build declares settings repositories");
        equal("skipped", load(f.root.resolve("no-settings-repositories/fixture/baseline-0.properties")).getProperty("catalogs"));
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: settings repositories added by a later callback come too late for the catalogs", () -> {
      try (final var f = new Fixture(true)) {
        Files.writeString(f.repo.resolve("settings.gradle"), "rootProject.name='offline-consumer'\n"
            + "gradle.settingsEvaluated { it.dependencyResolutionManagement.repositories.maven { url=uri('../maven') } }\n");
        f.commit();
        final var result = f.run("late", true, TARGET);
        expect(result, 1, "BLOCKED");
        contains(result.summary, "settings repositories added after the probe's settings hook ran");
        equal("late", load(f.root.resolve("late/fixture/baseline-0.properties")).getProperty("catalogs"));
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: a resolution failure that quotes credentials is masked in the probe report", () -> {
      try (final var f = new Fixture(true)) {
        Files.writeString(f.repo.resolve("build.gradle"), "configurations.runtimeClasspath.incoming.beforeResolve {\n"
            + "    throw new GradleException('refused Bearer in-probe_1 with \"token\":\"in-json\" at https://user:in-address@repo.example/x?key=in-query')\n}\n",
            StandardOpenOption.APPEND);
        f.commit();
        final var result = f.run("masked", true, TARGET);
        expect(result, 1, "BLOCKED");
        // The probe masks what it quotes from an exception; the helper relays that text as written.
        final var errors = load(f.root.resolve("masked/fixture/baseline-0.properties")).getProperty("errors");
        contains(errors, "refused Bearer [redacted] with \"token\":[redacted] at https://repo.example/x?redacted");
        contains(result.summary, "refused Bearer [redacted] with \"token\":[redacted] at https://repo.example/x?redacted");
        for (final var secret : List.of("in-probe_1", "in-json", "in-address", "in-query")) {
          require(!errors.contains(secret) && !result.summary.contains(secret), secret + " was not masked:\n" + errors);
        }
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: local plugin repository and BOM property overrides block", () -> {
      final var overrides = Map.of(
          "-PsavaBuildLocalRepo=/nonexistent", "savaBuildLocalRepo is set",
          "-PsolanaBOMVersion=" + TARGET, "Effective solanaBOMVersion " + TARGET + " differs from requested current version " + OLD);
      for (final var override : overrides.entrySet()) {
        try (final var f = new Fixture(true)) {
          f.registry.setProperty("consumer.fixture.gradleArgs", override.getKey());
          f.saveRegistry();
          f.commit();
          final var result = f.run("override", true, TARGET);
          expect(result, 1, "BLOCKED");
          contains(result.summary, override.getValue());
          equal("", f.git("status", "--porcelain"));
        }
      }
    });
    test("Gradle: subprojects traversed although the build asks for parallel execution", () -> {
      try (final var f = new Fixture(true)) {
        // The root becomes an aggregator without repositories; the dependencies move to a subproject.
        final var build = Files.readString(f.repo.resolve("build.gradle"));
        Files.writeString(f.repo.resolve("build.gradle"), "plugins { id 'base' }\n");
        Files.createDirectories(f.repo.resolve("sub"));
        Files.writeString(f.repo.resolve("sub/build.gradle"), build
            .replace("file('gradle/sava.properties')", "rootProject.file('gradle/sava.properties')")
            .replace("uri('../maven')", "uri('../../maven')")
            + "configurations { lazyTool { canBeConsumed = false } }\n");
        Files.writeString(f.repo.resolve("settings.gradle"), "include 'sub'\n", StandardOpenOption.APPEND);
        // Either setting alone defeats a probe that does not switch it off.
        Files.writeString(f.repo.resolve("gradle.properties"), "org.gradle.parallel=true\norg.gradle.configureondemand=true\n");
        f.commit();
        final var preview = f.run("preview", false, TARGET);
        expect(preview, 0, "WOULD UPDATE");
        contains(preview.summary, "fixture:leaf: 1 → 2");
        final var report = load(f.root.resolve("preview/fixture/baseline-0.properties"));
        contains(report.getProperty("configurations", ""), ":sub:project:runtimeClasspath");
        contains(report.getProperty("skipped_configurations", ""), ":sub:lazyTool (no declared dependencies)");
        expect(f.run("apply", true, TARGET), 0, "UPDATED");
        equal("gradle/sava.properties", f.git("diff", "--name-only"));
      }
    });
    test("Gradle: a catalog change outside the libraries updates conservatively", () -> {
      try (final var f = new Fixture(true)) {
        f.boms("1");
        for (final var version : List.of(OLD, TARGET)) {
          final var catalog = Path.of(f.pom(version).toString().replace(".pom", ".toml"));
          Files.writeString(catalog, "[versions]\ntool = \"" + (version.equals(OLD) ? "1" : "2") + "\"\n"
              + "[bundles]\nall = [\"leaf\", \"unused\"]\n"
              + "[plugins]\nfixture = { id = \"fixture.plugin\", version = \"1\" }\n", StandardOpenOption.APPEND);
        }
        f.verification();
        f.commit();
        final var result = f.run("shape", false, TARGET);
        expect(result, 0, "WOULD UPDATE");
        contains(result.summary, "catalog aliases, bundles, plugins, or version aliases changed");
        final var report = load(f.root.resolve("shape/fixture/baseline-0.properties"));
        equal("1", report.getProperty("current.shape.versionAlias.tool"));
        equal("2", report.getProperty("target.shape.versionAlias.tool"));
        equal("fixture:leaf,fixture:unused", report.getProperty("current.shape.bundle.all"));
        equal("fixture.plugin:1", report.getProperty("target.shape.plugin.fixture"));
        equal("", f.git("status", "--porcelain"));
      }
    });
    test("Gradle: an artifact missing only from the target graph surfaces after the edit, and a rerun continues", () -> {
      try (final var f = new Fixture(true)) {
        final var artifact = f.maven.resolve("fixture/leaf/2/leaf-2.jar");
        final var aside = f.root.resolve("leaf-2.jar");
        Files.move(artifact, aside);
        f.commit();
        expect(f.run("preview", false, TARGET), 0, "WOULD UPDATE");
        final var failed = f.run("apply", true, TARGET);
        expect(failed, 1, "NEEDS REPAIR");
        contains(failed.summary, "local pin edit retained");
        contains(Files.readString(f.pin), "solanaBOMVersion \t= " + TARGET);
        Files.move(aside, artifact);
        final var retry = f.run("retry", true, TARGET);
        expect(retry, 0, "UPDATED");
        contains(retry.summary, "| " + OLD + " | UPDATED | Validated the pin edit an earlier run left in place");
        equal("gradle/sava.properties", f.git("diff", "--name-only"));
      }
    });
  }

  static class Fixture implements AutoCloseable {

    final Path root = Files.createTempDirectory("catalog-java-tests-").toRealPath();
    final Path repo = root.resolve("consumer");
    final Path maven = root.resolve("maven");
    final Path pin = repo.resolve("gradle/sava.properties");
    final Properties config = new Properties();
    final Properties registry = new Properties();
    final boolean real;

    Fixture(final boolean real) throws Exception {
      this.real = real;
      Files.createDirectories(pin.getParent());
      Files.writeString(pin, "# retain me\r\nsolanaBOMVersion \t= " + OLD + "\r\notherVersion=" + OLD + "\r\n");
      Files.writeString(repo.resolve(".gitignore"), ".gradle/\nbuild/\n");
      Files.writeString(repo.resolve("tracked.txt"), "original\n");
      Files.writeString(repo.resolve("settings.gradle"), settings("../maven"));
      final var invocation = real ? quote(gradle.toString()) + " --offline" : quote(JAVA) + " " + quote(SOURCE.toString()) + " --fake-gradle";
      Files.writeString(repo.resolve("gradlew"), "#!/bin/sh\nexec " + invocation + " \"$@\"\n");
      repo.resolve("gradlew").toFile().setExecutable(true, true);
      Files.writeString(repo.resolve("build.gradle"), """
        plugins { id 'java-library' }
        def pins = new Properties()
        file('gradle/sava.properties').withInputStream { pins.load(it) }
        repositories { maven { url = uri('../maven') } }
        dependencies {
            implementation platform("software.sava:solana-version-catalog:${pins.solanaBOMVersion}")
            implementation 'fixture:used'
        }
        tasks.named('check') { doLast { configurations.runtimeClasspath.files } }
        """);
      registry.setProperty("version", "1");
      registry.setProperty("consumers", "fixture");
      registry.setProperty("consumer.fixture.path", "consumer");
      registry.setProperty("consumer.fixture.pin", "gradle/sava.properties");
      registry.setProperty("consumer.fixture.builds", ".");
      registry.setProperty("consumer.fixture.enabled", "true");
      registry.setProperty("consumer.fixture.validation.0", "./gradlew --offline --no-daemon check");
      saveRegistry();
      saveConfig();
      publish("fixture", "used", "1", "<dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId></dependency></dependencies>", "jar");
      publish("fixture", "leaf", "1", "", "jar");
      publish("fixture", "leaf", "2", "", "jar");
      boms("2");
    }

    String settings(final String relative) {
      return "rootProject.name='offline-consumer'\ndependencyResolutionManagement { repositories { maven { url=uri('" + relative + "') } } }\n";
    }

    void saveConfig() throws IOException {
      store(config, root.resolve("fake.properties"));
    }

    /** Registers a second build whose fake probe report takes the given shape. */
    void nested(final String shape, final String builds) throws IOException {
      config.setProperty("nested", shape);
      saveConfig();
      Files.createDirectories(repo.resolve("nested"));
      Files.writeString(repo.resolve("nested/settings.gradle"), "rootProject.name='nested'\n");
      registry.setProperty("consumer.fixture.builds", builds);
      saveRegistry();
    }

    void saveRegistry() throws IOException {
      store(registry, root.resolve("consumers.properties"));
    }

    Path pom(final String version) {
      return maven.resolve("software/sava/solana-version-catalog/" + version + "/solana-version-catalog-" + version + ".pom");
    }

    void boms(final String leafTarget) throws Exception {
      config.setProperty("leafTarget", leafTarget);
      saveConfig();
      for (final var version : List.of(OLD, TARGET)) {
        final var leaf = version.equals(OLD) ? "1" : leafTarget;
        final var unused = version.equals(OLD) ? "1" : "2";
        var constraints = "";
        for (final var entry : Map.of("used", "1", "leaf", leaf, "unused", unused).entrySet()) {
          constraints += "<dependency><groupId>fixture</groupId><artifactId>" + entry.getKey() + "</artifactId><version>" + entry.getValue() + "</version></dependency>";
        }
        final var stem = publish("software.sava", "solana-version-catalog", version, "<dependencyManagement><dependencies>" + constraints + "</dependencies></dependencyManagement>", "pom");
        Files.writeString(Path.of(stem + ".toml"), "[libraries]\nleaf={ module=\"fixture:leaf\", version=\"" + leaf + "\" }\nunused={ module=\"fixture:unused\", version=\"" + unused + "\" }\n");
        var constraintsJson = "";
        for (final var entry : Map.of("used", "1", "leaf", leaf, "unused", unused).entrySet()) {
          if (!constraintsJson.isEmpty()) {
            constraintsJson += ",";
          }
          constraintsJson += "{\"group\":\"fixture\",\"module\":\"" + entry.getKey() + "\",\"version\":{\"requires\":\"" + entry.getValue() + "\"}}";
        }
        final var catalogFile = stem.getFileName() + ".toml";
        var variants = "{\"name\":\"versionCatalogElements\",\"attributes\":{\"org.gradle.category\":\"platform\",\"org.gradle.usage\":\"version-catalog\"},\"files\":[{\"name\":\"" + catalogFile + "\",\"url\":\"" + catalogFile + "\"}]}";
        for (final var usage : List.of("java-api", "java-runtime")) {
          variants += ",{\"name\":\"" + usage + "\",\"attributes\":{\"org.gradle.category\":\"platform\",\"org.gradle.usage\":\"" + usage + "\"},\"dependencyConstraints\":[" + constraintsJson + "]}";
        }
        Files.writeString(Path.of(stem + ".module"), "{\"formatVersion\":\"1.1\",\"component\":{\"group\":\"software.sava\",\"module\":\"solana-version-catalog\",\"version\":\"" + version + "\"},\"variants\":[" + variants + "]}");
        Files.writeString(Path.of(stem + ".pom"), Files.readString(Path.of(stem + ".pom")).replace("</project>", "<!-- do_not_remove: published-with-gradle-metadata --></project>"));
      }
      verification();
    }

    static String pom(final String group, final String name, final String version, final String extra, final String packaging) {
      return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version><packaging>" + packaging + "</packaging>" + extra + "</project>";
    }

    Path publish(final String group, final String name, final String version, final String extra, final String packaging) throws Exception {
      final var dir = maven.resolve(group.replace('.', '/')).resolve(name).resolve(version);
      Files.createDirectories(dir);
      final var stem = dir.resolve(name + "-" + version);
      Files.writeString(Path.of(stem + ".pom"), pom(group, name, version, extra, packaging));
      if (packaging.equals("jar")) {
        try (final var zip = new ZipOutputStream(Files.newOutputStream(Path.of(stem + ".jar")))) {
          zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
          zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
          zip.closeEntry();
        }
      }
      return stem;
    }

    void verification() throws Exception {
      final var xml = new StringBuilder("<verification-metadata xmlns=\"https://schema.gradle.org/dependency-verification\"><configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration><components>");
      try (final var walk = Files.walk(maven)) {
        for (final var pom : walk.filter(p -> p.toString().endsWith(".pom")).sorted().toList()) {
          final var relative = maven.relativize(pom.getParent());
          final int n = relative.getNameCount();
          xml.append("<component group=\"").append(relative.subpath(0, n - 2).toString().replace('/', '.')).append("\" name=\"").append(relative.getName(n - 2)).append("\" version=\"").append(relative.getName(n - 1)).append("\">");
          try (final var files = Files.list(pom.getParent())) {
            for (final var artifact : files.sorted().toList()) {
              xml.append("<artifact name=\"").append(artifact.getFileName()).append("\"><sha256 value=\"").append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)))).append("\"/></artifact>");
            }
          }
          xml.append("</component>");
        }
      }
      Files.writeString(repo.resolve("gradle/verification-metadata.xml"), xml.append("</components></verification-metadata>").toString());
    }

    String git(final String... args) throws Exception {
      final var cmd = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false", "-C", repo.toString()));
      cmd.addAll(List.of(args));
      final var log = root.resolve("git.log");
      final var error = root.resolve("git.err");
      final var process = new ProcessBuilder(cmd).redirectOutput(log.toFile()).redirectError(error.toFile()).start();
      require(process.waitFor(30, TimeUnit.SECONDS), "Git fixture command timed out");
      final var text = Files.readString(log).trim();
      require(process.exitValue() == 0, Files.readString(error));
      return text;
    }

    void commit() throws Exception {
      commit("false");
    }

    void commit(final String autocrlf) throws Exception {
      git("init", "-q", "-b", "main");
      git("config", "core.autocrlf", autocrlf);
      git("add", ".");
      git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture");
    }

    Process start(final String name, final boolean apply, final String target, final String... extra) throws IOException {
      final var cmd = new ArrayList<>(List.of(JAVA, HELPER.toString(), "--version", target, "--workspace", root.toString(), "--registry", root.resolve("consumers.properties").toString(), "--report-dir", root.resolve(name).toString()));
      if (apply) {
        cmd.add("--apply");
      }
      cmd.addAll(List.of(extra));
      final var builder = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(root.resolve(name + ".log").toFile());
      // The caller's own Gradle overrides must not reach the fixtures.
      builder.environment().keySet().removeIf(key -> key.startsWith("ORG_GRADLE_PROJECT_") || key.equals("GRADLE_OPTS"));
      builder.environment().putAll(Map.of("GRADLE_USER_HOME", root.resolve("gradle-home").toString(), "CATALOG_TEST_FIXTURE", root.toString(), "CATALOG_TEST_KEEP", "fixture-sentinel"));
      if (Files.isDirectory(root.resolve("bin"))) {
        builder.environment().merge("PATH", root.resolve("bin").toString(), (path, bin) -> bin + File.pathSeparator + path);
      }
      return builder.start();
    }

    Result run(final String name, final boolean apply, final String target, final String... extra) throws Exception {
      final var report = root.resolve(name);
      final var log = root.resolve(name + ".log");
      final var process = start(name, apply, target, extra);
      if (!process.waitFor(180, TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        throw new AssertionError("Timed out: " + name);
      }
      final int code = process.exitValue();
      var diagnostics = Files.readString(log);
      if (Files.exists(report)) {
        try (final var walk = Files.walk(report)) {
          for (final var p : walk.filter(p -> p.toString().endsWith(".log")).sorted().toList()) {
            diagnostics += "\n" + p.getFileName() + ":\n" + Files.readString(p);
          }
        }
      }
      final var summary = report.resolve("summary.md");
      return new Result(code, Files.exists(summary) ? Files.readString(summary) : "", diagnostics);
    }

    public void close() throws IOException {
      try (final var walk = Files.walk(root)) {
        for (final var p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  static void fakeGradle(final String[] args) throws Exception {
    final var root = Path.of(System.getenv("CATALOG_TEST_FIXTURE"));
    final var config = load(root.resolve("fake.properties"));
    final var values = new HashMap<String, String>();
    for (final var arg : args) {
      if (arg.startsWith("-P") && arg.contains("=")) {
        final int split = arg.indexOf('=');
        values.put(arg.substring(2, split), arg.substring(split + 1));
      }
    }
    final boolean cold = !Path.of(System.getenv("GRADLE_USER_HOME")).equals(root.resolve("gradle-home"));
    if (cold && Boolean.parseBoolean(config.getProperty("checkCold"))) {
      final var copied = load(Path.of(System.getenv("GRADLE_USER_HOME"), "gradle.properties"));
      equal(Set.of("org.gradle.java.installations.paths", "org.gradle.java.installations.auto-download"), copied.stringPropertyNames());
      // Read as Gradle reads it, so the copy must be written in the properties file encoding.
      equal("/fixture/jdk-\u00e9", copied.getProperty("org.gradle.java.installations.paths"));
      equal("false", copied.getProperty("org.gradle.java.installations.auto-download"));
      equal("fixture-user", System.getenv("ORG_GRADLE_PROJECT_savaGithubPackagesUsername"));
      equal("fixture-secret", System.getenv("ORG_GRADLE_PROJECT_savaGithubPackagesPassword"));
      equal("fixture-sentinel", System.getenv("CATALOG_TEST_KEEP"));
      Files.writeString(root.resolve(values.containsKey("catalogPropagationOutput") ? "cold-probe" : "cold-validation"), "checked\n");
    }
    final var phase = values.containsKey("catalogPropagationOutput") ? "baseline" : "validation";
    if (phase.equals(config.getProperty("mutate"))) {
      Files.writeString(Path.of("tracked.txt"), "concurrent edit\n");
    }
    if (!values.containsKey("catalogPropagationOutput")) {
      final var marker = Path.of("build/validation-ran");
      Files.createDirectories(marker.getParent());
      Files.writeString(marker, "validated\n");
      if (Boolean.parseBoolean(config.getProperty("slowGit"))) {
        Files.writeString(root.resolve("git.slow"), "from here on\n");
      }
      final var snapshot = config.getProperty("snapshotSummary");
      if (snapshot != null) {
        Files.copy(root.resolve(snapshot).resolve("summary.md"), root.resolve("summary-during-validation.md"));
      }
      if (Boolean.parseBoolean(config.getProperty("validationHang"))) {
        // Moved into place, so that the test never reads a marker still being written.
        final var written = Files.writeString(root.resolve("validation.pid.tmp"), Long.toString(ProcessHandle.current().pid()));
        Files.move(written, root.resolve("validation.pid"), StandardCopyOption.ATOMIC_MOVE);
        Thread.sleep(TimeUnit.MINUTES.toMillis(3));
      }
      if (Boolean.parseBoolean(config.getProperty("validationFail"))) {
        // Shaped like a Gradle failure whose message quotes credentials in the forms the helper must mask.
        System.out.print("""

            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':check'.
            > boom https://user:in-address@repo.example/x?token=in-query Authorization: Bearer in-header.1
               {"token":"in-json"} sent Bearer after-scheme_1 over Basic authentication.
               Basic dXNlckxpbmVhcg or Bearer "in-quotes" with password=ab"in-tail and {\\"token\\":\\"in-escaped\\"}
               and password = "in spaces" or password="it's-in-apostrophe" then {"token":""} done

            * Try:
            > Run with --stacktrace option to get the stack trace.
            """);
        System.exit(1);
      }
      return;
    }
    // A build other than the root: "skipped" reads no catalog and uses a module of its own, "differs" reads another catalog.
    final var nested = Path.of("").toRealPath().equals(root.resolve("consumer")) ? "" : config.getProperty("nested", "");
    final boolean inspected = !nested.equals("skipped");
    final var report = new Properties();
    report.setProperty("schema_version", "2");
    report.setProperty("catalogs", inspected ? "inspected" : "skipped");
    report.setProperty("modules", Boolean.parseBoolean(config.getProperty("noModules")) ? ""
        : inspected ? "fixture:used\nfixture:leaf" : "fixture:tool");
    report.setProperty("configurations", ":runtimeClasspath");
    report.setProperty("errors", config.getProperty("probeErrors", ""));
    for (final var role : List.of("current", "target")) {
      final var version = values.get("catalogPropagation" + (role.equals("current") ? "Current" : "Target"));
      final var stem = root.resolve("maven/software/sava/solana-version-catalog/" + version + "/solana-version-catalog-" + version);
      report.setProperty(role + "_pom", stem + ".pom");
      report.setProperty(role + "_catalog", stem + ".toml");
      if (inspected) {
        report.setProperty(role + ".library.fixture:leaf", version.equals(OLD) ? "1"
            : nested.equals("differs") ? "9" : config.getProperty("catalogLeafTarget", config.getProperty("leafTarget", "2")));
        report.setProperty(role + ".shape.library.leaf", "fixture:leaf");
        if (config.getProperty("nested", "").equals("skipped")) {
          report.setProperty(role + ".library.fixture:tool", version.equals(OLD) ? "1" : "2");
          report.setProperty(role + ".shape.library.tool", "fixture:tool");
        }
        if (role.equals("target") && Boolean.parseBoolean(config.getProperty("shapeChange"))) {
          report.setProperty("target.shape.versionAlias.tool", "22");
        }
      }
    }
    store(report, Path.of(values.get("catalogPropagationOutput")));
    // Like the real probe: the report is stored first, then its findings fail the build.
    if (config.containsKey("probeErrors")) {
      System.exit(1);
    }
  }

  static Properties load(final Path path) throws IOException {
    final var result = new Properties();
    try (final var in = Files.newInputStream(path)) {
      result.load(in);
    }
    return result;
  }

  static void store(final Properties values, final Path path) throws IOException {
    try (final var out = Files.newOutputStream(path)) {
      values.store(out, "test fixture");
    }
  }

  static Path executable(final String name) {
    for (final var directory : System.getenv("PATH").split(File.pathSeparator)) {
      final var candidate = Path.of(directory, name);
      if (Files.isExecutable(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("Not on PATH: " + name);
  }

  static String quote(final String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  static void require(final boolean condition, final String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }

  static void equal(final Object expected, final Object actual) {
    require(Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
  }

  static void contains(final String value, final String expected) {
    require(value.contains(expected), "Missing " + expected + " in:\n" + value);
  }

  static void expect(final Result result, final int code, final String status) {
    require(result.code == code && result.summary.contains(status), "Expected exit " + code + "/" + status + ":\n" + result.diagnostics + "\n" + result.summary);
  }

  static void test(final String name, final Check check) {
    try {
      check.run();
      passed++;
      System.out.println("PASS " + name);
    } catch (final Throwable failure) {
      failed++;
      System.err.println("FAIL " + name);
      failure.printStackTrace();
    }
  }
}
