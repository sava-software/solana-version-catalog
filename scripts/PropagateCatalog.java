import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Local BOM propagation. Never commits, publishes, pushes, or deploys.
 */
public class PropagateCatalog {

  static final Path ROOT = Path.of(System.getProperty("jdk.launcher.sourcefile",
      "scripts/PropagateCatalog.java")).toAbsolutePath().normalize().getParent().getParent();
  static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
  static final Pattern PIN = Pattern.compile("(?m)^(solanaBOMVersion[ \\t]*=[ \\t]*)([^\\r\\n]*)(\\r?$)");
  static final Pattern URL = Pattern.compile("(?i)(https?://)(?:[^\\s/'\"<>@]*@)?([^\\s'\"<>?]*)(\\?[^\\s'\"<>]*)?");
  /**
   * A named secret: a quoted value through its closing quote, any other up to white space, a comma or a
   * semicolon. Only character-class loops, so that no input can exhaust the stack or the time.
   */
  static final Pattern SECRET = Pattern.compile(
      "(?i)(password|token|authorization)((?:\\\\?[\"'])?\\s*[:=]\\s*)(?:(?:bearer|basic|token)\\s+)?(?:\\\\?\"[^\"\\r\\n]*\"?|\\\\?'[^'\\r\\n]*'?|[^\\s,;]+)");
  /** A credential after its scheme word alone; a plain lower-case word after it is prose and stays. */
  static final Pattern SCHEME = Pattern.compile(
      "(?i:\\b(bearer|basic))(\\s+)(?![a-z]+[.)\\]\"'!?:]*(?:[\\s,;]|\\z))[^\\s,;]+");
  static final String REGISTRY_FIELDS = "path|pin|enabled|builds|gradleArgs|coldResolve|branch|notes|validation\\.[0-9]+";
  /** The child process a shutdown must not leave running in a consumer checkout. */
  static volatile Process running;
  /** Set when the JVM starts to shut down: no further child starts, and one that ended since is not a result. */
  static volatile boolean stopping;
  /** Released when the run has recorded its outcome; the shutdown hook holds the JVM until then. */
  static final CountDownLatch RECORDED = new CountDownLatch(1);

  static class Blocked extends Exception {

    Blocked(final String message) {
      super(message);
    }
  }

  interface Checkpoint {

    void write() throws IOException;
  }

  record Consumer(String name,
                  String path,
                  String pin,
                  boolean enabled,
                  List<String> builds,
                  List<List<String>> validation,
                  List<String> gradleArgs,
                  boolean coldResolve,
                  String branch,
                  String notes) {
  }

  record Pin(String version, String source) {
  }

  static class Row {

    final String name;
    String checkout = "—";
    String current = "—";
    String status = "BLOCKED";
    String detail = "";

    Row(final String name) {
      this.name = name;
    }
  }

  static class Options {

    Path workspace = ROOT.getParent();
    Path registry = ROOT.resolve("downstream/consumers.properties");
    Path reportDir;
    String version;
    boolean apply;
    boolean list;
    boolean help;
    final Set<String> consumers = new LinkedHashSet<>();
  }

  static Properties properties(final Path path) throws IOException {
    final var result = new Properties() {

      @Override
      public synchronized Object put(final Object key, final Object value) {
        if (containsKey(key)) {
          throw new IllegalArgumentException("Duplicate property key: " + key);
        }
        return super.put(key, value);
      }
    };
    try (final var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      result.load(reader);
    }
    return result;
  }

  static String required(final Properties properties, final String key) throws Blocked {
    final var value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new Blocked("Missing property: " + key);
    }
    return value;
  }

  static boolean bool(final Properties properties, final String key, final boolean fallback) throws Blocked {
    final var value = properties.getProperty(key, Boolean.toString(fallback));
    if (!value.equals("true") && !value.equals("false")) {
      throw new Blocked("Expected true/false: " + key);
    }
    return Boolean.parseBoolean(value);
  }

  static List<String> words(final String value) {
    return value.isBlank() ? List.of() : List.of(value.trim().split("\\s+"));
  }

  static List<String> csv(final String value) throws Blocked {
    final var result = Arrays.stream(value.split(",", -1)).map(String::trim).toList();
    if (result.stream().anyMatch(String::isBlank) || new HashSet<>(result).size() != result.size()) {
      throw new Blocked("Empty or duplicate comma-separated entry");
    }
    return result;
  }

  static List<Consumer> registry(final Path path) throws Exception {
    final var p = properties(path);
    if (!"1".equals(p.getProperty("version"))) {
      throw new Blocked("Unsupported registry; expected version=1");
    }
    final var result = new ArrayList<Consumer>();
    final var paths = new HashSet<Path>();
    final var names = csv(required(p, "consumers"));
    for (final var name : names) {
      if (!name.matches("[a-zA-Z0-9_-]+")) {
        throw new Blocked("Invalid consumer name: " + name);
      }
    }
    // A misspelled field or a block for an unlisted consumer would otherwise drop a required check
    // silently. Checked first, so that the mistake is named rather than reported by its effect.
    final var known = Pattern.compile("consumer\\.(" + String.join("|", names) + ")\\.(" + REGISTRY_FIELDS + ")");
    for (final var key : new TreeSet<>(p.stringPropertyNames())) {
      if (!key.equals("version") && !key.equals("consumers") && !known.matcher(key).matches()) {
        throw new Blocked("Unknown registry key: " + key);
      }
    }
    for (final var name : names) {
      final var prefix = "consumer." + name + ".";
      final var repo = required(p, prefix + "path");
      if (!paths.add(Path.of(repo).normalize())) {
        throw new Blocked("Duplicate consumer path: " + repo);
      }
      final boolean enabled = bool(p, prefix + "enabled", true);
      final var builds = csv(p.getProperty(prefix + "builds", "."));
      if (!builds.contains(".")) {
        throw new Blocked(name + ": builds must include the root build");
      }
      final var commands = new TreeMap<Integer, List<String>>();
      for (final var key : p.stringPropertyNames()) {
        if (key.startsWith(prefix + "validation.")) {
          final int index;
          try {
            index = Integer.parseInt(key.substring((prefix + "validation.").length()));
          } catch (final NumberFormatException error) {
            throw new Blocked("Invalid validation index: " + key);
          }
          final var args = words(p.getProperty(key));
          if (index < 0 || args.isEmpty() || !args.getFirst().equals("./gradlew") ||
              commands.put(index, args) != null) {
            throw new Blocked(name + ": validation must contain literal ./gradlew argument arrays");
          }
        }
      }
      for (int i = 0; i < commands.size(); i++) {
        if (!commands.containsKey(i)) {
          throw new Blocked(name + ": validation indexes must start at 0 with no gaps");
        }
      }
      if (enabled && commands.isEmpty()) {
        throw new Blocked(name + ": no validation commands registered");
      }
      final var notes = p.getProperty(prefix + "notes", "").strip();
      if (!enabled && notes.isEmpty()) {
        throw new Blocked(name + ": a disabled consumer needs notes giving the reason");
      }
      final var branch = p.getProperty(prefix + "branch");
      if (branch != null && branch.isBlank()) {
        throw new Blocked(name + ": branch must not be blank");
      }
      result.add(new Consumer(name, repo, p.getProperty(prefix + "pin", "gradle/sava.properties"), enabled,
          builds, new ArrayList<>(commands.values()), words(p.getProperty(prefix + "gradleArgs", "")),
          bool(p, prefix + "coldResolve", false), branch == null ? null : branch.strip(), notes));
    }
    return result;
  }

  static Path contained(final Path base, final String relative) throws Exception {
    final var input = Path.of(relative);
    final var root = base.toRealPath();
    var path = root.resolve(input).normalize();
    if (input.isAbsolute() || !path.startsWith(root)) {
      throw new Blocked("Path escapes its root: " + relative);
    }
    if (Files.exists(path)) {
      path = path.toRealPath();
    } else {
      var parent = path.getParent();
      while (parent != null && !Files.exists(parent)) {
        parent = parent.getParent();
      }
      if (parent != null && !parent.toRealPath().startsWith(root)) {
        throw new Blocked("Path escapes its root: " + relative);
      }
    }
    if (!path.startsWith(root)) {
      throw new Blocked("Path escapes its root: " + relative);
    }
    return path;
  }

  static int compareVersions(final String first, final String second) throws Blocked {
    for (final var version : List.of(first, second)) {
      if (!VERSION.matcher(version).matches()) {
        throw new Blocked("Expected an exact numeric release version, got " + version);
      }
    }
    final var a = first.split("\\.");
    final var b = second.split("\\.");
    for (int i = 0; i < 3; i++) {
      final int c = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /** Ends the run once the JVM has begun to shut down. */
  static void stopped(final String when) throws InterruptedException {
    if (stopping) {
      throw new InterruptedException("Stopped " + when);
    }
  }

  /**
   * The same check for a child that has ended. One that a signal ended has usually shared the signal
   * with this JVM, whose shutdown hook may not have run yet, so that case waits briefly for the hook.
   */
  static void stopped(final String when, final int code) throws InterruptedException {
    for (int i = 0; i < 40 && code > 128 && !stopping; i++) {
      Thread.sleep(50);
    }
    stopped(when);
  }

  static String gitRaw(final Path repo, final String... args) throws Exception {
    final var command = new ArrayList<String>(List.of("git", "-C", repo.toString()));
    command.addAll(List.of(args));
    stopped("before git " + args[0]);
    final var errors = Files.createTempFile("catalog-git-", ".log");
    try {
      final var process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
      try {
        final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int code = process.waitFor();
        // A check that ended while the run was being stopped is not a result.
        stopped("during git " + args[0], code);
        if (code != 0) {
          throw new Blocked("Git check failed in " + repo + ": " + Files.readString(errors).strip());
        }
        return output;
      } catch (final InterruptedException interrupted) {
        stopProcess(process);
        throw interrupted;
      }
    } finally {
      Files.deleteIfExists(errors);
    }
  }

  static String git(final Path repo, final String... args) throws Exception {
    return gitRaw(repo, args).strip();
  }

  static String checkoutHead(final Path repo) throws Exception {
    if (!Files.isDirectory(repo)) {
      throw new Blocked("Checkout is missing");
    }
    if (!Path.of(git(repo, "rev-parse", "--show-toplevel")).toRealPath().equals(repo)) {
      throw new Blocked("Registry path is not a Git checkout root");
    }
    return git(repo, "rev-parse", "HEAD");
  }

  static String relative(final Path repo, final Path file) {
    return repo.relativize(file).toString().replace(File.separatorChar, '/');
  }

  /**
   * An apply run leaves its pin edit in place when validation fails or the run is interrupted.
   * Returns the committed pin version when that edit to the target, and nothing else, differs
   * from HEAD; otherwise null, and the checkout counts as carrying someone's local work.
   */
  static String retainedPin(final Path repo, final Path pin, final String status, final String target) throws Exception {
    final var path = relative(repo, pin);
    if (!status.equals(" M " + path + "\0") || !git(repo, "diff", "HEAD", "--summary", "--", path).isEmpty()) {
      return null;
    }
    final var removed = new ArrayList<String>();
    final var added = new ArrayList<String>();
    boolean hunk = false;
    final var diff = gitRaw(repo, "diff", "HEAD", "--no-color", "--no-ext-diff", "--no-textconv", "-U0", "--", path);
    for (final var line : diff.split("\n", -1)) {
      if (line.startsWith("@@")) {
        hunk = true;
      } else if (hunk && line.startsWith("-")) {
        removed.add(line.substring(1));
      } else if (hunk && line.startsWith("+")) {
        added.add(line.substring(1));
      }
    }
    if (removed.size() != 1 || added.size() != 1) {
      return null;
    }
    final var committed = PIN.matcher(removed.getFirst());
    if (!committed.matches() || !changedPin(removed.getFirst(), target).equals(added.getFirst())) {
      return null;
    }
    final var version = committed.group(2).strip();
    return VERSION.matcher(version).matches() && compareVersions(version, target) < 0 ? version : null;
  }

  static Pin readPin(final Path path) throws Exception {
    final var source = Files.readString(path);
    final var match = PIN.matcher(source);
    if (!match.find()) {
      throw new Blocked("Expected exactly one solanaBOMVersion assignment in " + path);
    }
    final var version = match.group(2).strip();
    if (match.find()) {
      throw new Blocked("Expected exactly one solanaBOMVersion assignment in " + path);
    }
    compareVersions(version, version);
    // The build reads this file as Java properties, which also accept an indented key, ':' or
    // whitespace as the separator, and keep the last of several assignments.
    final var parsed = new Properties();
    try {
      parsed.load(new StringReader(source));
    } catch (final IllegalArgumentException malformed) {
      throw new Blocked("Malformed properties in " + path);
    }
    if (!version.equals(Objects.toString(parsed.getProperty("solanaBOMVersion"), "").strip())) {
      throw new Blocked("The build would read a different solanaBOMVersion than the one assignment found in " + path);
    }
    // A later assignment of the same value in one of those other forms would survive the edit.
    final var marker = "edited";
    final var edited = new Properties();
    edited.load(new StringReader(changedPin(source, marker)));
    if (!marker.equals(Objects.toString(edited.getProperty("solanaBOMVersion"), "").strip())) {
      throw new Blocked("The build would not read an edit of the one solanaBOMVersion assignment found in " + path);
    }
    return new Pin(version, source);
  }

  static String changedPin(final String source, final String target) {
    return PIN.matcher(source).replaceFirst(match ->
        Matcher.quoteReplacement(match.group(1) + target + match.group(3)));
  }

  static void bumpPin(final Path path, final String source, final String changed) throws Exception {
    if (!Files.readString(path).equals(source)) {
      throw new Blocked("BOM pin changed during inspection");
    }
    final var temporary = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".tmp");
    try {
      Files.writeString(temporary, changed);
      try {
        Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path));
      } catch (final UnsupportedOperationException ignored) {
        /* Non-POSIX filesystems retain their normal permissions. */
      }
      if (!Files.readString(path).equals(source)) {
        throw new Blocked("BOM pin changed during update");
      }
      Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void unchanged(final Path repo,
                        final String head,
                        final Path pin,
                        final String expected,
                        final boolean edited) throws Exception {
    if (!git(repo, "rev-parse", "HEAD").equals(head) || !Files.readString(pin).equals(expected)) {
      throw new Blocked("Checkout HEAD or pin changed during inspection/validation");
    }
    final var status = gitRaw(repo, "status", "--porcelain", "-z", "--untracked-files=normal");
    final var allowed = edited ? " M " + relative(repo, pin) + "\0" : "";
    if (!status.equals(allowed) ||
        (edited && !git(repo, "diff", "--summary", "--", relative(repo, pin)).isEmpty())) {
      throw new Blocked("Checkout changed during inspection/validation; preserve and review concurrent work");
    }
  }

  static Element child(final Element parent, final String name) {
    for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof final Element e && name.equals(e.getLocalName())) {
        return e;
      }
    }
    return null;
  }

  static String field(final Element parent, final String name) {
    final var e = child(parent, name);
    return e == null ? null : e.getTextContent().strip();
  }

  static Map<String, String> pomVersions(final Path path, final String expected) throws Exception {
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    final var root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    if (!"http://maven.apache.org/POM/4.0.0".equals(root.getNamespaceURI()) ||
        !"software.sava".equals(field(root, "groupId")) ||
        !"solana-version-catalog".equals(field(root, "artifactId")) ||
        !expected.equals(field(root, "version"))) {
      throw new Blocked("Unexpected published BOM identity in " + path);
    }
    if (child(root, "parent") != null) {
      throw new Blocked("Inherited BOMs require an effective-model implementation");
    }
    final var managed = child(root, "dependencyManagement");
    final var dependencies = managed == null ? null : child(managed, "dependencies");
    final var result = new TreeMap<String, String>();
    if (dependencies != null) {
      for (var n = dependencies.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (!(n instanceof final Element dependency) || !"dependency".equals(dependency.getLocalName())) {
          continue;
        }
        final var group = field(dependency, "groupId");
        final var artifact = field(dependency, "artifactId");
        final var version = field(dependency, "version");
        if (group == null || artifact == null || version == null ||
            group.isBlank() || artifact.isBlank() || version.isBlank() ||
            (group + artifact + version).contains("${")) {
          throw new Blocked("BOM has missing or unresolved dependency coordinates");
        }
        if ("import".equals(field(dependency, "scope")) || field(dependency, "classifier") != null ||
            (field(dependency, "type") != null && !"jar".equals(field(dependency, "type")))) {
          throw new Blocked("Imported BOMs/classified or non-jar constraints require explicit support");
        }
        if (result.put(group + ":" + artifact, version) != null) {
          throw new Blocked("Duplicate BOM constraint: " + group + ":" + artifact);
        }
      }
    }
    if (result.isEmpty()) {
      throw new Blocked("Published BOM contains no constraints");
    }
    return result;
  }

  static Map<String, String> prefixed(final Properties properties, final String prefix) {
    final var result = new TreeMap<String, String>();
    for (final var key : properties.stringPropertyNames()) {
      if (key.startsWith(prefix)) {
        result.put(key.substring(prefix.length()), properties.getProperty(key));
      }
    }
    return result;
  }

  static Set<String> lines(final String value) {
    return new TreeSet<>(value.lines().filter(s -> !s.isBlank()).toList());
  }

  static Map<String, String> catalogEntries(final Properties report) {
    final var result = new TreeMap<String, String>();
    for (final var prefix : List.of("current.shape.", "target.shape.", "current.library.", "target.library.")) {
      prefixed(report, prefix).forEach((key, value) -> result.put(prefix + key, value));
    }
    return result;
  }

  /**
   * The published catalogs do not depend on the consuming build, so one registered build that can
   * import them through settings repositories answers for every build of the checkout.
   */
  static Properties catalogs(final List<Properties> reports) throws Blocked {
    Properties result = null;
    boolean late = false;
    for (final var report : reports) {
      late |= report.getProperty("catalogs").equals("late");
      if (report.getProperty("catalogs").equals("inspected")) {
        if (result == null) {
          result = report;
        } else if (!catalogEntries(result).equals(catalogEntries(report))) {
          throw new Blocked("Registered builds read different published catalogs");
        }
      }
    }
    if (result == null) {
      throw new Blocked(late
          ? "No registered build could read the published catalogs: settings repositories added after the probe's settings hook ran, as a later settingsEvaluated callback adds them, come too late for its catalog import"
          : "No registered build declares settings repositories, so none could read the published catalogs");
    }
    return result;
  }

  static List<String> impact(final Properties report,
                             final Properties catalogs,
                             final String current,
                             final String target) throws Exception {
    final var old = pomVersions(Path.of(required(report, "current_pom")), current);
    final var next = pomVersions(Path.of(required(report, "target_pom")), target);
    final var modules = lines(report.getProperty("modules", ""));
    final var changed = new TreeSet<String>();
    final var result = new ArrayList<String>();
    for (final var module : modules) {
      if (!Objects.equals(old.get(module), next.get(module))) {
        changed.add(module);
        result.add(module + ": " + old.getOrDefault(module, "absent") + " → " + next.getOrDefault(module, "removed"));
      }
    }
    if (!prefixed(catalogs, "current.shape.").equals(prefixed(catalogs, "target.shape."))) {
      result.add("catalog aliases, bundles, plugins, or version aliases changed (conservative update)");
    }
    final var oldCatalog = prefixed(catalogs, "current.library.");
    final var newCatalog = prefixed(catalogs, "target.library.");
    for (final var module : modules) {
      if (!changed.contains(module) && !Objects.equals(oldCatalog.get(module), newCatalog.get(module))) {
        result.add("catalog version for " + module + ": " + oldCatalog.getOrDefault(module, "absent") +
            " → " + newCatalog.getOrDefault(module, "removed"));
      }
    }
    return result;
  }

  static void runCommand(final List<String> command,
                         final Path cwd,
                         final Path log,
                         final Map<String, String> environment) throws Exception {
    System.out.println("  " + cwd.getFileName() + ": running Gradle (" + log.getFileName() + ")");
    final var builder = new ProcessBuilder(command).directory(cwd.toFile())
        .redirectErrorStream(true).redirectOutput(log.toFile());
    if (environment != null) {
      builder.environment().clear();
      builder.environment().putAll(environment);
    }
    stopped("before running " + log.getFileName());
    final var process = builder.start();
    running = process;
    final int code;
    try {
      // The shutdown hook may have looked for a running child just before this one was recorded.
      stopped("while starting " + log.getFileName());
      code = process.waitFor();
    } catch (final InterruptedException interrupted) {
      stopProcess(process);
      throw interrupted;
    } finally {
      running = null;
    }
    // A child that the hook, or the signal behind it, stopped: its exit code says nothing about the check.
    stopped("during " + log.getFileName(), code);
    if (code != 0) {
      final var reason = failureReason(log);
      throw new Blocked("Command exited " + code + (reason.isEmpty() ? "" : ": " + reason) + "; inspect " + log);
    }
  }

  static void stopProcess(final Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  static String redact(final String text) {
    final var addresses = URL.matcher(text).replaceAll(match ->
        Matcher.quoteReplacement(match.group(1) + match.group(2) + (match.group(3) == null ? "" : "?redacted")));
    return SCHEME.matcher(SECRET.matcher(addresses).replaceAll("$1$2[redacted]")).replaceAll("$1$2[redacted]");
  }

  /** Gradle's own statement of the failure, so that the summary says why without opening the log. */
  static String failureReason(final Path log) {
    final String text;
    try {
      text = Files.readString(log);
    } catch (final IOException unreadable) {
      return "";
    }
    final var heading = "* What went wrong:";
    final int start = text.indexOf(heading);
    if (start < 0) {
      return "";
    }
    final int end = text.indexOf("* Try:", start);
    final var reason = redact(text.substring(start + heading.length(), end < 0 ? text.length() : end).lines()
        .map(String::strip)
        .map(line -> line.startsWith("> ") ? line.substring(2) : line)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining(" ")));
    return reason.length() > 600 ? reason.substring(0, 600) + "…" : reason;
  }

  static List<Properties> probe(final Path repo,
                                final Consumer consumer,
                                final String current,
                                final String target,
                                final Path output,
                                final String phase,
                                final Map<String, String> environment) throws Exception {
    final var reports = new ArrayList<Properties>();
    int index = 0;
    for (final var build : consumer.builds) {
      final var directory = contained(repo, build);
      if (!Files.isRegularFile(directory.resolve("settings.gradle")) &&
          !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
        throw new Blocked("Registered build has no settings file: " + build);
      }
      var wrapper = directory.resolve("gradlew");
      if (!Files.isRegularFile(wrapper)) {
        wrapper = repo.resolve("gradlew");
      }
      final var report = output.resolve(phase + "-" + index + ".properties");
      final var log = output.resolve(phase + "-" + index + ".log");
      // One root task resolves every project's graphs, which parallel execution refuses, and
      // configure-on-demand would not register that task.
      final var command = new ArrayList<String>(List.of(
          wrapper.toString(), "--no-daemon", "--no-configuration-cache", "--no-parallel", "--no-configure-on-demand",
          "--console=plain", "--dependency-verification=strict",
          "-I", ROOT.resolve("downstream/inspect.init.gradle").toString(),
          "-PcatalogPropagationOutput=" + report, "-PcatalogPropagationCheckoutRoot=" + repo,
          "-PcatalogPropagationCurrent=" + current, "-PcatalogPropagationTarget=" + target, "catalogPropagationReport"));
      command.addAll(consumer.gradleArgs);
      try {
        runCommand(command, directory, log, environment);
      } catch (final Blocked failure) {
        // The probe stores its report before it fails the build, so its findings outrank the exit code.
        if (Files.isRegularFile(report)) {
          final var errors = lines(properties(report).getProperty("errors", ""));
          if (!errors.isEmpty()) {
            throw new Blocked(String.join("; ", errors) + "; inspect " + log);
          }
        }
        throw failure;
      }
      final var p = properties(report);
      if (!"2".equals(p.getProperty("schema_version")) ||
          !List.of("inspected", "skipped", "late").contains(p.getProperty("catalogs", ""))) {
        throw new Blocked("Unsupported Gradle probe report");
      }
      if (!p.getProperty("errors", "").isBlank()) {
        throw new Blocked(p.getProperty("errors").replace('\n', ';'));
      }
      if (p.getProperty("configurations", "").isBlank()) {
        throw new Blocked("No dependency graph coverage for build " + build);
      }
      if (!p.containsKey("modules")) {
        throw new Blocked("Missing dependency modules in probe report");
      }
      reports.add(p);
      index++;
    }
    return reports;
  }

  static Map<String, String> coldEnvironment(final Path destination) throws Exception {
    final var environment = new HashMap<String, String>(System.getenv());
    environment.put("GRADLE_USER_HOME", destination.toString());
    final var home = Path.of(System.getenv().getOrDefault("GRADLE_USER_HOME",
        Path.of(System.getProperty("user.home"), ".gradle").toString()));
    if (Files.isRegularFile(home.resolve("gradle.properties"))) {
      // Read as Gradle reads it: the last of a repeated key wins, in the properties file encoding.
      final var global = new Properties();
      try (final var in = Files.newInputStream(home.resolve("gradle.properties"))) {
        global.load(in);
      }
      final var toolchain = new Properties();
      for (final var key : global.stringPropertyNames()) {
        if (key.startsWith("org.gradle.java.")) {
          toolchain.setProperty(key, global.getProperty(key));
        } else if (key.equals("savaGithubPackagesUsername") || key.equals("savaGithubPackagesPassword")) {
          environment.put("ORG_GRADLE_PROJECT_" + key, global.getProperty(key));
        }
      }
      if (!toolchain.isEmpty()) {
        final var path = destination.resolve("gradle.properties");
        // Stored as a stream, which escapes what the properties file encoding cannot hold.
        try (final var out = Files.newOutputStream(path)) {
          toolchain.store(out, "Java toolchain settings only");
        }
        try {
          Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (final UnsupportedOperationException ignored) {
        }
      }
    }
    return environment;
  }

  static void deleteTree(final Path path) throws IOException {
    try (final var paths = Files.walk(path)) {
      for (final var entry : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }

  static void validationCommands(final Path repo,
                                 final Consumer consumer,
                                 final Path output,
                                 final String prefix,
                                 final Map<String, String> environment) throws Exception {
    int index = 0;
    for (final var registered : consumer.validation) {
      final var command = new ArrayList<String>(registered);
      command.add("--dependency-verification=strict");
      command.addAll(consumer.gradleArgs);
      runCommand(command, repo, output.resolve(prefix + "validation-" + index++ + ".log"), environment);
    }
  }

  static void validate(final Path repo,
                       final Consumer consumer,
                       final String target,
                       final Path output) throws Exception {
    probe(repo, consumer, target, target, output, "target", null);
    validationCommands(repo, consumer, output, "", null);
    if (consumer.coldResolve) {
      final var home = Files.createTempDirectory("catalog-cold-");
      try {
        final var environment = coldEnvironment(home);
        probe(repo, consumer, target, target, output, "cold", environment);
        validationCommands(repo, consumer, output, "cold-", environment);
      } finally {
        // A leftover temporary home must neither fail a validated consumer nor hide a real failure.
        try {
          deleteTree(home);
        } catch (final IOException failure) {
          System.err.println("  Warning: could not remove temporary Gradle home " + home + ": " + describe(failure));
        }
      }
    }
  }

  static String describe(final Exception error) {
    final var message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    return switch (error) {
      case NoSuchFileException _ -> "Missing file: " + message;
      case FileAlreadyExistsException _ -> "Already exists: " + message;
      case AccessDeniedException _ -> "Access denied: " + message;
      default -> message;
    };
  }

  static void process(final Consumer consumer,
                      final Options args,
                      final Path runDir,
                      final Row row,
                      final Checkpoint checkpoint) throws InterruptedException {
    boolean edited = false;
    boolean validating = false;
    try {
      final var repo = contained(args.workspace, consumer.path);
      final var head = checkoutHead(repo);
      // The full name: a tag called like the branch makes the short one ambiguous.
      final var ref = git(repo, "rev-parse", "--symbolic-full-name", "HEAD");
      final boolean detached = !ref.startsWith("refs/heads/");
      final var branch = detached ? null : ref.substring("refs/heads/".length());
      row.checkout = (detached ? "detached" : branch) + " @ " + head.substring(0, Math.min(10, head.length()));
      if (consumer.branch != null && !consumer.branch.equals(branch)) {
        throw new Blocked("Checkout is on " + (detached ? "a detached HEAD" : branch) +
            "; the registry expects " + consumer.branch);
      }
      if (Files.isSymbolicLink(repo.resolve(consumer.pin))) {
        throw new Blocked("BOM pin must be a regular file, not a symlink");
      }
      final var pin = contained(repo, consumer.pin);
      final var status = gitRaw(repo, "status", "--porcelain", "-z", "--untracked-files=normal");
      // Only apply mode may continue from its own earlier pin edit; everything else needs a clean checkout.
      final var retained = status.isEmpty() || !args.apply ? null : retainedPin(repo, pin, status, args.version);
      if (!status.isEmpty() && retained == null) {
        throw new Blocked("Checkout has local changes; preserve/review them before propagation");
      }
      final var before = readPin(pin);
      row.current = retained == null ? before.version : retained;
      edited = retained != null;
      final int comparison = compareVersions(before.version, args.version);
      if (comparison > 0) {
        row.status = "NEWER";
        row.detail = "Kept newer pin; no downgrade";
        return;
      }
      final var output = Files.createDirectory(runDir.resolve(consumer.name));
      if (comparison == 0) {
        if (args.apply) {
          if (edited) {
            row.status = "IN PROGRESS";
            row.detail = "Validation of the pin edit an earlier run left in place had started when this report was written";
            checkpoint.write();
          }
          validating = true;
          validate(repo, consumer, args.version, output);
          unchanged(repo, head, pin, before.source, edited);
        }
        row.status = edited ? "UPDATED" : "CURRENT";
        row.detail = edited ? "Validated the pin edit an earlier run left in place"
            : args.apply ? "Validated" : "Already pinned; not revalidated";
        return;
      }
      final var detail = new TreeSet<String>();
      final var reports = probe(repo, consumer, before.version, args.version, output, "baseline", null);
      final var catalogs = catalogs(reports);
      boolean modules = false;
      for (final var report : reports) {
        modules |= !lines(report.getProperty("modules", "")).isEmpty();
        detail.addAll(impact(report, catalogs, before.version, args.version));
      }
      if (!modules) {
        throw new Blocked("No published module in any inspected graph; dependency use cannot be established");
      }
      unchanged(repo, head, pin, before.source, false);
      if (detail.isEmpty()) {
        row.status = "UNAFFECTED";
        row.detail = "No changed dependency in the inspected graphs";
        return;
      }
      final var changes = String.join("; ", detail);
      row.detail = changes;
      if (!args.apply) {
        row.status = "WOULD UPDATE";
        return;
      }
      final var expected = changedPin(before.source, args.version);
      // Recorded first, so that an interrupted run still leaves a report of the edit it made.
      row.status = "IN PROGRESS";
      row.detail = "Pin edit and validation had started when this report was written; " + changes;
      checkpoint.write();
      bumpPin(pin, before.source, expected);
      edited = true;
      validating = true;
      validate(repo, consumer, args.version, output);
      unchanged(repo, head, pin, expected, true);
      row.status = "UPDATED";
      row.detail = changes;
    } catch (final Exception error) {
      if (error instanceof final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      row.status = edited || validating ? "NEEDS REPAIR" : "BLOCKED";
      row.detail = describe(error) + (edited ? "; local pin edit retained for repair/review"
          : validating ? "; the pin was already at the target and this run edited nothing" : "");
    }
  }

  static String cell(final String value) {
    return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  static void summary(final List<Row> rows,
                      final Options args,
                      final Path directory,
                      final int selected,
                      final boolean finished) throws IOException {
    final var text = new StringBuilder("# Catalog propagation to " + args.version + "\n\nMode: " +
        (args.apply ? "apply and validate" : "preview (no source edits)") + ". Request: " +
        (args.consumers.isEmpty() ? "all registered consumers" : String.join(", ", args.consumers)) +
        ".\n\n| Consumer | Checkout | Previous pin | Result | Detail |\n|---|---|---|---|---|\n");
    for (final var row : rows) {
      text.append("| ").append(cell(row.name)).append(" | ").append(cell(row.checkout)).append(" | ")
          .append(cell(row.current)).append(" | ").append(cell(row.status)).append(" | ")
          .append(cell(row.detail)).append(" |\n");
    }
    text.append("\nConsumers in this report: ").append(rows.size()).append(" of ").append(selected)
        .append(finished ? ".\n" : ". The run had not finished when this report was written.\n");
    text.append("\nNo commits, pushes, releases, or deployments were performed.\n\nExcluded consumers are outside the enabled registry; coverage is not ecosystem-wide.\n");
    Files.writeString(directory.resolve("summary.md"), text);
  }

  static Options options(final String[] argv) throws Blocked {
    final var result = new Options();
    for (int i = 0; i < argv.length; i++) {
      final var argument = argv[i];
      switch (argument) {
        case "--apply" -> {
          result.apply = true;
        }
        case "--list" -> {
          result.list = true;
        }
        case "--help", "-h" -> {
          result.help = true;
        }
        case "--version", "--workspace", "--registry", "--consumer", "--report-dir" -> {
          if (++i == argv.length) {
            throw new Blocked("Missing value for " + argument);
          }
          final var value = argv[i];
          switch (argument) {
            case "--version" -> {
              result.version = value;
            }
            case "--workspace" -> {
              result.workspace = Path.of(value);
            }
            case "--registry" -> {
              result.registry = Path.of(value);
            }
            case "--consumer" -> {
              result.consumers.add(value);
            }
            case "--report-dir" -> {
              result.reportDir = Path.of(value);
            }
          }
        }
        default -> {
          throw new Blocked("Unknown argument: " + argument);
        }
      }
    }
    result.workspace = result.workspace.toAbsolutePath().normalize();
    return result;
  }

  static int run(final String[] argv) throws Exception {
    final var args = options(argv);
    if (args.help) {
      System.out.println("Usage: java scripts/PropagateCatalog.java --version X.Y.Z [--apply] [--consumer NAME] [--workspace DIR] [--registry FILE] [--report-dir NEW_DIR]\n       java scripts/PropagateCatalog.java --list\nPrepares local updates only; never commits, pushes, publishes, or deploys.");
      return 0;
    }
    final var registered = registry(args.registry);
    final var unknown = new TreeSet<String>(args.consumers);
    registered.forEach(c -> unknown.remove(c.name));
    if (!unknown.isEmpty()) {
      throw new Blocked("Unknown consumers: " + String.join(", ", unknown));
    }
    final var selected = registered.stream()
        .filter(c -> args.consumers.isEmpty() || args.consumers.contains(c.name))
        .toList();
    if (args.list) {
      for (final var c : selected) {
        String pin;
        try {
          pin = readPin(contained(contained(args.workspace, c.path), c.pin)).version;
        } catch (final Exception error) {
          pin = describe(error);
        }
        System.out.println(c.name + ": " + (c.enabled ? "enabled" : "excluded") + "; " + pin + "; " + c.path);
      }
      return 0;
    }
    if (args.version == null) {
      throw new Blocked("--version is required unless --list is used");
    }
    compareVersions(args.version, args.version);
    final var output = (args.reportDir == null
        ? ROOT.resolve("build/propagation/" + Instant.now().toString().replace(':', '-'))
        : args.reportDir).toAbsolutePath().normalize();
    Files.createDirectories(output.getParent());
    Files.createDirectory(output);
    final var rows = new ArrayList<Row>();
    final var counts = new LinkedHashMap<String, Integer>();
    boolean failed = false;
    for (final var consumer : selected) {
      stopped("before " + consumer.name);
      System.out.println(consumer.name + ": inspecting");
      final var row = new Row(consumer.name);
      rows.add(row);
      if (consumer.enabled) {
        process(consumer, args, output, row, () -> summary(rows, args, output, selected.size(), false));
      } else {
        row.status = "EXCLUDED";
        row.detail = consumer.notes;
      }
      counts.merge(row.status, 1, Integer::sum);
      System.out.println("  " + row.status + " [" + row.checkout + "]: " + row.detail);
      summary(rows, args, output, selected.size(), false);
      failed |= row.status.equals("BLOCKED") || row.status.equals("NEEDS REPAIR") ||
          (!args.consumers.isEmpty() && row.status.equals("EXCLUDED"));
    }
    summary(rows, args, output, selected.size(), true);
    System.out.println("\n" + String.join(", ", counts.entrySet().stream()
        .map(e -> e.getValue() + " " + e.getKey().toLowerCase(Locale.ROOT)).toList()));
    System.out.println("Report: " + output.resolve("summary.md"));
    return failed ? 1 : 0;
  }

  public static void main(final String[] args) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      stopping = true;
      final var process = running;
      if (process != null) {
        stopProcess(process);
      }
      // The run thread then reports the stop; the JVM halts once it has, or after a bounded wait.
      try {
        RECORDED.await(10, TimeUnit.SECONDS);
      } catch (final InterruptedException ignored) {
        /* The JVM is halting either way. */
      }
    }));
    int code;
    try {
      code = run(args);
    } catch (final Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println("Error: " + describe(error));
      code = 2;
    } finally {
      RECORDED.countDown();
    }
    System.exit(code);
  }
}
