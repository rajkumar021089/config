package config;

import com.wm.app.b2b.server.*;
import com.wm.data.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** All implementation is visible in Designer: individual Source tabs and Shared Source. */
public final class services {
  public static final String BUILD_ID = "config-2.3.3-20261102";
  private static volatile ConfigStore STORE;
  private static final byte[] TOKEN_KEY = new byte[32];

  static {
    new SecureRandom().nextBytes(TOKEN_KEY);
  }

  /** Read one pipeline field and release the cursor immediately. */
  private static String input(IData p, String key) {
    IDataCursor c = p.getCursor();
    try {
      return IDataUtil.getString(c, key);
    } finally {
      c.destroy();
    }
  }

  private static void output(IData p, String key, Object value) {
    IDataCursor c = p.getCursor();
    try {
      IDataUtil.put(c, key, value);
    } finally {
      c.destroy();
    }
  }

  private static boolean flag(String value, boolean fallback) {
    if (value == null || value.isBlank()) return fallback;
    if ("true".equalsIgnoreCase(value)) return true;
    if ("false".equalsIgnoreCase(value)) return false;
    throw new IllegalArgumentException("Boolean flags must be true, false or blank");
  }

  /**
   * Cache API reflection metadata, NOT authorization results. Recheck the caller on each
   * invocation.
   */
  private static final class Authorization {
    static final Method GET, ALLOW;

    static {
      try {
        Class<?> c = Class.forName("com.wm.app.b2b.server.ACLManager");
        GET = c.getMethod("getGroup", String.class);
        Method allow = null;
        for (Method m : c.getMethods())
          if (m.getName().equals("allow")
              && m.getParameterCount() == 2
              && m.getParameterTypes()[0].isAssignableFrom(GET.getReturnType())
              && m.getParameterTypes()[1].isAssignableFrom(Service.getUser().getClass())) {
            allow = m;
            break;
          }
        if (allow == null) throw new IllegalStateException("IS ACL API unavailable");
        ALLOW = allow;
      } catch (Exception e) {
        throw new ExceptionInInitializerError("Unable to initialize IS ACL validation");
      }
    }
  }

  private static void authorize(String acl) throws Exception {
    Object user = Service.getUser();
    if (user == null) throw new SecurityException("Authentication required");
    Object group = Authorization.GET.invoke(null, acl);
    if (group == null || !Boolean.TRUE.equals(Authorization.ALLOW.invoke(null, group, user)))
      throw new SecurityException("Access denied");
  }

  private static String username() throws Exception {
    Object user = Service.getUser();
    if (user == null) throw new SecurityException();
    return String.valueOf(user.getClass().getMethod("getName").invoke(user));
  }

  /**
   * Initialization is synchronized once. Normal reads touch the volatile reference and immutable
   * maps.
   */
  private static ConfigStore store() throws Exception {
    ConfigStore s = STORE;
    if (s == null)
      synchronized (services.class) {
        s = STORE;
        if (s == null) {
          String explicit = System.getProperty("config.packagesRoot");
          Path root;
          if (explicit != null) root = Paths.get(explicit);
          else {
            Class<?> server = Class.forName("com.wm.app.b2b.server.Server");
            Object resources = server.getMethod("getResources").invoke(null);
            File dir =
                (File)
                    resources
                        .getClass()
                        .getMethod("getPackageConfigDir", String.class)
                        .invoke(resources, "config");
            root = dir.toPath().getParent().getParent();
          }
          s = new ConfigStore(root);
          s.reset();
          STORE = s;
        }
      }
    return s;
  }

  private static ServiceException failure(Exception e) {
    if (e instanceof SecurityException) return new ServiceException("config: access denied");
    // Do not expose legacy exception messages: they could contain passwords or connection strings.
    return new ServiceException(
        "config: "
            + (e instanceof ConfigException
                ? e.getMessage()
                : "Operation failed; check installation, permissions and key provisioning"));
  }

  public static class ConfigException extends Exception {
    public ConfigException(String message) {
      super(message);
    }
  }

  public static final class MissingKey extends ConfigException {
    public MissingKey() {
      super("Configuration key not found");
    }
  }

  private static String q(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\').append(c);
      else if (c < 32) b.append(String.format("\\u%04x", (int) c));
      else b.append(c);
    }
    return b.append('"').toString();
  }

  private static String array(Collection<String> values) {
    StringJoiner j = new StringJoiner(",", "[", "]");
    for (String v : values) j.add(q(v));
    return j.toString();
  }

  private static String mac(String text) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(TOKEN_KEY, "HmacSHA256"));
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String token() throws Exception {
    String t = Long.toString(System.currentTimeMillis());
    return t + "." + mac(username() + "\n" + t);
  }

  private static void checkToken(String token) throws Exception {
    if (token == null) throw new SecurityException();
    String[] p = token.split("\\.", -1);
    if (p.length != 2) throw new SecurityException();
    long age;
    try {
      age = System.currentTimeMillis() - Long.parseLong(p[0]);
    } catch (NumberFormatException e) {
      throw new SecurityException();
    }
    if (age < 0
        || age > 1800000
        || !MessageDigest.isEqual(
            mac(username() + "\n" + p[0]).getBytes(StandardCharsets.US_ASCII),
            p[1].getBytes(StandardCharsets.US_ASCII))) throw new SecurityException();
  }

  private static void registerMenu(boolean add) throws Exception {
    IData p = IDataFactory.create();
    output(p, "callback", "config.services:menu");
    Service.doInvoke("wm.server.ui", add ? "addSolution" : "removeSolution", p);
  }

  /** Startup loads the saved config files into this package's JVM snapshot. */
  public static void startup(IData pipeline) throws ServiceException {
    try {
      store();
      try {
        registerMenu(true);
      } catch (Exception e) {
        System.err.println("config: menu registration unavailable; use /config/index.dsp");
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public static void shutdown(IData pipeline) throws ServiceException {
    try {
      authorize("Administrators");
      registerMenu(false);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public static void menu(IData pipeline) throws ServiceException {
    output(pipeline, "name", "config");
    output(pipeline, "text", "Configuration Management");
    output(pipeline, "url", "../config/index.dsp");
    output(pipeline, "target", "configConsole");
  }

  /**
   * Wrapper contract: input field names match the supplied WxConfig signature; output is
   * propertyValue.
   */
  public static void getValue(IData pipeline) throws ServiceException {
    try {
      authorize(System.getProperty("config.runtimeAcl", "Administrators"));
      // Unsupported interpolation is rejected explicitly, rather than silently changing values.
      if (flag(input(pipeline, "substituteVariables"), false))
        throw new ConfigException("substituteVariables=true is outside the agreed scope");
      flag(input(pipeline, "noVariableInterpolation"), true);
      ConfigStore s = store();
      ConfigStore.Snapshot snap = s.snapshot();
      String value =
          s.lookup(
              snap,
              input(pipeline, "wxConfigPkgName"),
              input(pipeline, "key"),
              input(pipeline, "configFile"),
              flag(input(pipeline, "ignoreGlobalValues"), false),
              flag(input(pipeline, "scanAllConfigurations"), false),
              flag(input(pipeline, "showEncryptedValues"), false));
      output(pipeline, "propertyValue", value);
    } catch (MissingKey e) {
      // Suppression applies to a missing key, never to access denial or decryption failure.
      try {
        if (flag(input(pipeline, "noServiceException"), false)) {
          output(pipeline, "propertyValue", null);
          return;
        }
      } catch (Exception invalid) {
        throw failure(invalid);
      }
      throw failure(e);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** Bulk lookup captures one snapshot so all returned values belong to the same version. */
  public static void getValues(IData pipeline) throws ServiceException {
    try {
      authorize(System.getProperty("config.runtimeAcl", "Administrators"));
      ConfigStore s = store();
      ConfigStore.Snapshot snap = s.snapshot();
      IDataCursor c = pipeline.getCursor();
      String[] keys;
      try {
        keys = IDataUtil.getStringArray(c, "keys");
      } finally {
        c.destroy();
      }
      if (keys == null || keys.length > 1000) throw new ConfigException("Supply at most 1000 keys");
      IData values = IDataFactory.create();
      boolean reveal = flag(input(pipeline, "showEncryptedValues"), false);
      for (String key : keys)
        output(
            values,
            key,
            s.lookup(
                snap,
                input(pipeline, "wxConfigPkgName"),
                key,
                null,
                flag(input(pipeline, "ignoreGlobalValues"), false),
                false,
                reveal));
      output(pipeline, "values", values);
      output(pipeline, "configurationVersion", snap.version);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** Obtain a short-lived admin token for DSP mutations. */
  public static void getAdminToken(IData pipeline) throws ServiceException {
    try {
      authorize("Administrators");
      output(pipeline, "token", token());
    } catch (Exception e) {
      throw failure(e);
    }
  }

  public static void resetServerCache(IData pipeline) throws ServiceException {
    try {
      authorize("Administrators");
      checkToken(input(pipeline, "token"));
      ConfigStore.Snapshot snap = store().reset();
      output(pipeline, "configurationVersion", snap.version);
      output(pipeline, "loadedAt", snap.loadedAt);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /** Retained endpoint for old callers; importing legacy configuration is disabled. */
  public static void migrate(IData pipeline) throws ServiceException {
    try {
      authorize("Administrators");
      checkToken(input(pipeline, "token"));
      MigrationResult r =
          store()
              .migrate(
                  input(pipeline, "sourceService"),
                  input(pipeline, "packagePrefix"),
                  flag(input(pipeline, "dryRun"), true),
                  input(pipeline, "planId"),
                  flag(input(pipeline, "activate"), true));
      output(pipeline, "result", r.json);
      output(pipeline, "planId", r.planId);
      output(pipeline, "migrationStatus", r.status);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  /**
   * DSP backend. The browser receives metadata and ciphertext/handles; never automatic plaintext
   * secrets.
   */
  public static void dispatch(IData pipeline) throws ServiceException {
    try {
      authorize("Administrators");
      ConfigStore s = store();
      String action = input(pipeline, "action"),
          pkg = input(pipeline, "packageName"),
          file = input(pipeline, "fileName"),
          result;
      if (action == null || action.equals("status")) {
        ConfigStore.Snapshot snap = s.snapshot();
        result =
            "{\"token\":"
                + q(token())
                + ",\"environment\":"
                + q(snap.environment)
                + ",\"loadedAt\":"
                + q(snap.loadedAt)
                + ",\"version\":"
                + q(snap.version)
                + ",\"packages\":"
                + array(s.listPackages())
                + s.discoveryJson()
                + "}";
      } else if (action.equals("files")) {
        result = "{\"files\":" + array(s.listFiles(pkg)) + s.fileDiagnostics(pkg) + "}";
      } else if (action.equals("read")) {
        String text = s.read(pkg, file);
        result = "{\"content\":" + q(text) + ",\"version\":" + q(ConfigStore.hash(text)) + "}";
      } else if (action.equals("secretsAll")) {
        result = s.allSecretMetadata();
      } else if (action.equals("secretEditInfo")) {
        result = s.secretEditInfo(pkg);
      } else if (action.equals("secrets")) {
        result = s.secretMetadata(pkg);
      } else {
        checkToken(input(pipeline, "token"));
        switch (action) {
          case "save":
            result =
                "{\"version\":"
                    + q(s.save(pkg, file, input(pipeline, "content"), input(pipeline, "version")))
                    + ",\"message\":\"Saved. Active cache unchanged.\"}";
            break;
          case "create":
            result =
                "{\"version\":"
                    + q(s.save(pkg, file, "# Package configuration\n", "NEW"))
                    + ",\"message\":\"File created. Cache unchanged.\"}";
            break;
          case "saveHandle":
            s.saveHandle(pkg, input(pipeline, "handle"), input(pipeline, "secret"), input(pipeline, "version"));
            output(pipeline, "secret", null);
            result = "{\"message\":\"Encrypted value saved to disk. Reload Configuration to activate.\"}";
            break;
          case "saveSecret":
            s.saveSecret(
                pkg,
                file,
                input(pipeline, "key"),
                input(pipeline, "handle"),
                input(pipeline, "secret"),
                input(pipeline, "version"));
            output(pipeline, "secret", null);
            result = "{\"message\":\"Encrypted value saved. Reset cache to activate.\"}";
            break;
          // Combined administration action: validate/publish configuration BEFORE clearing service
          // results.
          // If configuration validation fails, the catch below reports an error and the built-in
          // reset is NOT called.
          case "resetAll":
            {
              ConfigStore.Snapshot snap = s.reset();
              boolean resultsReset = false;
              try {
                Service.doInvoke(
                    "pub.cache.serviceResults", "resetServerCache", IDataFactory.create());
                resultsReset = true;
              } catch (Exception resetFailure) {
                // Configuration already reloaded successfully. Do not falsely report a total
                // failure
                // or roll back a live configuration snapshot after service-result invalidation
                // failed.
              }
              String message =
                  resultsReset
                      ? "Configuration reloaded; IS service-result caches reset on this node."
                      : "Configuration reloaded, but IS service-result cache reset failed. Check"
                            + " built-in service access and the IS server log.";
              result =
                  "{\"configurationReloaded\":true,\"serviceResultsReset\":"
                      + resultsReset
                      + ",\"version\":"
                      + q(snap.version)
                      + ",\"message\":"
                      + q(message)
                      + "}";
              break;
            }
          case "reset":
            ConfigStore.Snapshot snap = s.reset();
            result = "{\"message\":\"Server cache refreshed\",\"version\":" + q(snap.version) + "}";
            break;
          case "migrate":
            result =
                s.migrate(
                        input(pipeline, "sourceService"),
                        input(pipeline, "packagePrefix"),
                        flag(input(pipeline, "dryRun"), true),
                        input(pipeline, "planId"),
                        flag(input(pipeline, "activate"), true))
                    .json;
            break;
          default:
            throw new ConfigException("Unknown operation");
        }
      }
      output(pipeline, "result", result);
    } catch (Exception e) {
      output(pipeline, "secret", null);
      if (e instanceof SecurityException) throw failure(e);
      output(
          pipeline,
          "result",
          "{\"error\":"
              + q(
                  e instanceof ConfigException
                      ? e.getMessage()
                      : "Operation failed. Check installation, access and key provisioning.")
              + "}");
    }
  }

  /** Local JVM configuration engine. No remote server or disk access occurs during lookup. */
  public static final class ConfigStore {
    private final Path root;
    private volatile Snapshot active;
    private volatile SecretKeySpec encryptionKey;
    private static final String SECRET_FILE = "config.secrets.cnf", PREFIX = "ENCv1:";
    private static final Pattern REFERENCE =
        Pattern.compile("\\[\\[encrypted:([^\\]\\r\\n]+)\\]\\]");
    private static final SecureRandom RANDOM = new SecureRandom();

    // Admin discovery settings are loaded once at startup and refreshed by explicit reload.
    private volatile Discovery discovery;
    private static final class Discovery {
      final String prefix;
      final List<String> directories, commonFiles;
      Discovery(Properties settings) throws Exception {
        prefix = settings.getProperty("packagePrefix", "NBK").trim();
        if (!prefix.matches("[A-Za-z0-9_]*")) throw new ConfigException("Invalid packagePrefix");
        directories = entries(settings.getProperty("configurationDirectories", "config,Config"), false);
        commonFiles = entries(settings.getProperty("commonConfigFiles", "config.cnf"), true);
      }
      static List<String> entries(String value, boolean file) throws Exception {
        List<String> result = new ArrayList<>();
        for (String item : value.split(",", -1)) {
          item = item.trim();
          if (!item.matches(file ? "[A-Za-z0-9_][A-Za-z0-9_-]*\\.cnf" : "[A-Za-z0-9_][A-Za-z0-9_-]*"))
            throw new ConfigException("Invalid configuration directory or common filename");
          // Existing manager settings cannot re-enable legacy files after an upgrade.
          if (file && item.toLowerCase(Locale.ROOT).startsWith("wxconfig")) continue;
          if (!result.contains(item)) result.add(item);
        }
        if (file && result.isEmpty()) result.add("config.cnf");
        return Collections.unmodifiableList(result);
      }
    }
    private Properties managerSettings() throws Exception {
      Path dir = packageDir("config").resolve("config");
      if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir))
        throw new ConfigException("Manager settings directory unavailable");
      Path settings = dir.resolve("manager.properties");
      if (Files.isSymbolicLink(settings)) throw new ConfigException("Unsafe manager settings path");
      return parse(readPath(settings));
    }
    private String logicalFile(String filename) {
      for (String common : discovery.commonFiles) {
        String stem = common.substring(0, common.length() - 4);
        if (filename.equalsIgnoreCase(common)) return "config.cnf";
        if (filename.toLowerCase(Locale.ROOT).startsWith(stem.toLowerCase(Locale.ROOT) + "-"))
          return "config" + filename.substring(stem.length()).toLowerCase(Locale.ROOT);
      }
      return filename.toLowerCase(Locale.ROOT);
    }
    public String discoveryJson() {
      return ",\"packagePrefix\":" + q(discovery.prefix)
          + ",\"commonConfigFiles\":" + array(discovery.commonFiles)
          + ",\"configurationDirectories\":" + array(discovery.directories)
          + ",\"packagesRoot\":" + q(root.toString())
          + ",\"buildId\":" + q(BUILD_ID);
    }
    public String fileDiagnostics(String pkg) throws Exception {
      Path parent = packageDir(pkg);
      List<String> searched = new ArrayList<>();
      List<String> found = new ArrayList<>();
      for (String folder : discovery.directories) {
        Path candidate = parent.resolve(folder);
        searched.add(candidate.toString());
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) found.add(candidate.toString());
      }
      return ",\"buildId\":" + q(BUILD_ID)
          + ",\"searchedDirectories\":" + array(searched)
          + ",\"existingDirectories\":" + array(found);
    }

    public static final class Snapshot {
      public final String environment, loadedAt, version, globalPackage;
      public final Map<String, Map<String, String>> packages, files, secrets, clearSecrets;
      private final SecretKeySpec key;
      private volatile String encryptedInventory;

      Snapshot(
          String env,
          String version,
          String global,
          Map<String, Map<String, String>> packages,
          Map<String, Map<String, String>> files,
          Map<String, Map<String, String>> secrets,
          Map<String, Map<String, String>> clear,
          SecretKeySpec key) {
        environment = env;
        loadedAt = Instant.now().toString();
        this.version = version;
        globalPackage = global;
        this.packages = freeze(packages);
        this.files = freeze(files);
        this.secrets = freeze(secrets);
        clearSecrets = freeze(clear);
        this.key = key;
      }
    }

    public ConfigStore(Path root) throws IOException {
      this.root = root.toRealPath();
      try { discovery = new Discovery(managerSettings()); } catch (Exception e) { throw new IOException("Cannot load manager discovery settings", e); }
    }

    private static Map<String, Map<String, String>> freeze(
        Map<String, Map<String, String>> source) {
      Map<String, Map<String, String>> result = new HashMap<>();
      for (Map.Entry<String, Map<String, String>> e : source.entrySet())
        result.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
      return Collections.unmodifiableMap(result);
    }

    private static String name(String name) throws ConfigException {
      if (name == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || name.contains(".."))
        throw new ConfigException("Invalid package name");
      return name;
    }

    private Path packageDir(String pkg) throws Exception {
      Path p = root.resolve(name(pkg));
      if (Files.isSymbolicLink(p)
          || !Files.isDirectory(p)
          || !Files.isRegularFile(p.resolve("manifest.v3"))
          || !p.toRealPath().getParent().equals(root))
        throw new ConfigException("Unknown or unsafe package");
      return p;
    }

    private Path directory(String pkg) throws Exception {
      Path parent = packageDir(pkg);
      Path p = null;
      for (String folder : discovery.directories) {
        Path candidate = parent.resolve(folder);
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
          if (p != null) throw new ConfigException("Multiple configured directories exist in package " + pkg);
          p = candidate;
        }
      }
      if (p == null) throw new ConfigException("Package configuration directory unavailable");
      if (Files.isSymbolicLink(p)
          || !Files.isDirectory(p)
          || !p.toRealPath().getParent().equals(packageDir(pkg).toRealPath()))
        throw new ConfigException("Package config directory unavailable");
      return p;
    }

    private Path file(String pkg, String filename) throws Exception {
      String pattern = "(?i)(?:config(?:-[A-Za-z0-9_]+)?\\.cnf|config\\.secrets\\.cnf)";
      if (filename == null || filename.toLowerCase(Locale.ROOT).startsWith("wxconfig")
          || (!filename.matches(pattern) && !logicalFile(filename).matches("config(?:-[A-Za-z0-9_]+)?\\.cnf")))
        throw new ConfigException("Unsupported configuration filename");
      Path p = directory(pkg).resolve(filename);
      if (Files.isSymbolicLink(p)
          || (Files.exists(p) && !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)))
        throw new ConfigException("Unsafe configuration path");
      return p;
    }

    public List<String> listPackages() throws Exception {
      List<String> result = new ArrayList<>();
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
        for (Path p : ds)
          try {
            // Discover existing business packages; the config manager itself is not a business package.
            String name = p.getFileName().toString();
            if (!name.startsWith(discovery.prefix)) continue;
            packageDir(name);
            result.add(name);
          } catch (Exception skip) {
          }
      }
      Collections.sort(result);
      return result;
    }

    public List<String> listFiles(String pkg) throws Exception {
      packageDir(pkg);
      List<String> result = new ArrayList<>();
      boolean found = false;
      for (String folder : discovery.directories) if (Files.exists(root.resolve(pkg).resolve(folder), LinkOption.NOFOLLOW_LINKS)) found = true;
      if (!found) return result;
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory(pkg))) {
        for (Path p : ds)
          try {
            file(pkg, p.getFileName().toString());
            result.add(p.getFileName().toString());
          } catch (Exception skip) {
          }
      }
      Collections.sort(result);
      return result;
    }

    private static Properties parse(String text) throws Exception {
      if (text == null || text.length() > 1048576)
        throw new ConfigException("Configuration exceeds 1 MiB or is missing");
      Properties p = new Properties();
      try {
        p.load(new StringReader(text));
      } catch (IllegalArgumentException e) {
        throw new ConfigException("Invalid properties syntax");
      }
      return p;
    }

    private static String serialize(Properties values) throws IOException {
      StringWriter w = new StringWriter();
      values.store(w, "config managed properties");
      return w.toString();
    }

    private static Map<String, String> map(Properties p) {
      Map<String, String> values = new HashMap<>();
      for (String key : p.stringPropertyNames()) values.put(key, p.getProperty(key));
      return values;
    }

    private static String readPath(Path path) throws Exception {
      if (Files.size(path) > 1048576) throw new ConfigException("Configuration exceeds 1 MiB");
      return Files.readString(path, StandardCharsets.UTF_8);
    }

    public String read(String pkg, String filename) throws Exception {
      return readPath(file(pkg, filename));
    }

    public static String hash(String text) throws Exception {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String handle(String value) {
      Matcher m = REFERENCE.matcher(value);
      return m.matches() ? m.group(1) : null;
    }

    /** Prepare temporary files first; roll back already-replaced targets if a later move fails. */
    private static void writeBatch(Map<Path, String> changes) throws Exception {
      Map<Path, String> old = new LinkedHashMap<>();
      Map<Path, Path> temporary = new LinkedHashMap<>();
      List<Path> moved = new ArrayList<>();
      try {
        for (Map.Entry<Path, String> e : changes.entrySet()) {
          Path dest = e.getKey();
          old.put(dest, Files.exists(dest) ? readPath(dest) : null);
          Path temp = Files.createTempFile(dest.getParent(), ".config-", ".tmp");
          temporary.put(dest, temp);
          try {
            Files.setPosixFilePermissions(
                temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
          } catch (UnsupportedOperationException windows) {
          }
          Files.writeString(temp, e.getValue(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<Path, Path> e : temporary.entrySet()) {
          Files.move(
              e.getValue(),
              e.getKey(),
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
          moved.add(e.getKey());
        }
      } catch (Exception failure) {
        boolean rollbackFailed = false;
        for (Path dest : moved)
          try {
            String text = old.get(dest);
            if (text == null) Files.deleteIfExists(dest);
            else {
              Path undo = Files.createTempFile(dest.getParent(), ".config-undo-", ".tmp");
              try {
                Files.writeString(undo, text, StandardCharsets.UTF_8);
                Files.move(
                    undo,
                    dest,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
              } finally {
                Files.deleteIfExists(undo);
              }
            }
          } catch (Exception rollback) {
            rollbackFailed = true;
          }
        throw new ConfigException(
            rollbackFailed
                ? "Write failed and rollback incomplete; restore from backup"
                : "Write failed; changed targets rolled back");
      } finally {
        for (Path temp : temporary.values()) Files.deleteIfExists(temp);
      }
    }

    /** Save changes disk only. Never assign active or call reset here. */
    public synchronized String save(String pkg, String filename, String text, String expected)
        throws Exception {
      Properties values = parse(text);
      Path path = file(pkg, filename);
      if (SECRET_FILE.equals(filename))
        for (String v : values.stringPropertyNames())
          if (!values.getProperty(v).startsWith(PREFIX))
            throw new ConfigException("Secret store accepts encrypted entries only");
      String actual = Files.exists(path) ? hash(readPath(path)) : "NEW";
      if (!Objects.equals(expected, actual))
        throw new ConfigException("File changed; reopen before saving");
      writeBatch(Collections.singletonMap(path, text));
      return hash(text);
    }

    public synchronized void saveSecret(
        String pkg, String filename, String key, String secretHandle, String plain, String expected)
        throws Exception {
      if (SECRET_FILE.equals(filename) || key == null || key.isBlank() || plain == null)
        throw new ConfigException("Select a normal config file and supply key/value");
      if (secretHandle == null || secretHandle.isBlank()) secretHandle = key;
      if (secretHandle.contains("]") || secretHandle.contains("\n") || secretHandle.contains("\r"))
        throw new ConfigException("Invalid secret handle");
      Path target = file(pkg, filename);
      String text = readPath(target);
      if (!Objects.equals(expected, hash(text)))
        throw new ConfigException("File changed; reopen before saving");
      Properties p = parse(text);
      p.setProperty(key, "[[encrypted:" + secretHandle + "]]");
      Path secretPath = file(pkg, SECRET_FILE);
      Properties secrets =
          Files.exists(secretPath) ? parse(readPath(secretPath)) : new Properties();
      secrets.setProperty(secretHandle, encrypt(pkg, secretHandle, plain));
      Map<Path, String> changes = new LinkedHashMap<>();
      changes.put(secretPath, serialize(secrets));
      changes.put(target, serialize(p));
      writeBatch(changes);
    }

    /** Cache the provisioned key once. Runtime decryption never reads its file or environment. */
    private SecretKeySpec key() throws Exception {
      SecretKeySpec found = encryptionKey;
      if (found != null) return found;
      synchronized (this) {
        found = encryptionKey;
        if (found != null) return found;
        String encoded = System.getenv("CONFIG_MASTER_KEY");
        if (encoded == null || encoded.isBlank()) {
          String location = System.getProperty("config.masterKeyFile");
          if (location == null || location.isBlank())
            location = managerSettings().getProperty("masterKeyFile", "").trim();
          if (location.isBlank())
            throw new ConfigException("Set masterKeyFile in config/config/manager.properties to a protected external key file");
          Path p = Paths.get(location);
          if (!p.isAbsolute() || p.toRealPath().startsWith(root))
            throw new ConfigException("Key file must be outside packages");
          encoded = Files.readString(p, StandardCharsets.US_ASCII).trim();
        }
        byte[] bytes;
        try {
          bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
          throw new ConfigException("Invalid master key encoding");
        }
        if (bytes.length != 32) throw new ConfigException("Master key must contain 32 bytes");
        found = new SecretKeySpec(bytes, "AES");
        encryptionKey = found;
        return found;
      }
    }

    public String encrypt(String pkg, String handle, String plain) throws Exception {
      packageDir(pkg);
      byte[] iv = new byte[12];
      RANDOM.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      c.updateAAD((pkg + "\n" + handle).getBytes(StandardCharsets.UTF_8));
      return PREFIX
          + Base64.getEncoder().encodeToString(iv)
          + ":"
          + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String decrypt(SecretKeySpec key, String pkg, String handle, String cipher)
        throws Exception {
      try {
        if (key == null || !cipher.startsWith(PREFIX)) throw new GeneralSecurityException();
        String[] p = cipher.substring(PREFIX.length()).split(":", -1);
        if (p.length != 2) throw new GeneralSecurityException();
        byte[] iv = Base64.getDecoder().decode(p[0]);
        if (iv.length != 12) throw new GeneralSecurityException();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        c.updateAAD((pkg + "\n" + handle).getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(Base64.getDecoder().decode(p[1])), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new ConfigException("Secret resolution failed; verify key, package and handle");
      }
    }

    /** Load and validate privately, then one volatile assignment publishes the complete version. */
    public synchronized Snapshot reset() throws Exception {
      Discovery previous = discovery;
      Properties settings = managerSettings();
      discovery = new Discovery(settings);
      try { return loadSnapshot(settings); }
      catch (Exception failure) { discovery = previous; throw failure; }
    }
    private Snapshot loadSnapshot(Properties settings) throws Exception {
      String env = settings.getProperty("environment", "dev");
      if (!env.matches("[A-Za-z0-9_]+")) throw new ConfigException("Invalid environment");
      boolean cacheClear = flag(settings.getProperty("cacheDecryptedSecrets"), false);
      String global = settings.getProperty("globalPackage", "");
      Map<String, Map<String, String>> packages = new HashMap<>(),
          files = new HashMap<>(),
          secrets = new HashMap<>(),
          clear = new HashMap<>();
      StringBuilder fingerprint = new StringBuilder(env + "\n" + global + "\n");
      SecretKeySpec key = null;
      // Startup and explicit reload share this disk-to-memory path. Never scan on getValue.
      List<String> loadPackages = new ArrayList<>(listPackages());
      // Preserve an explicitly configured common fallback, even if outside the NBK prefix.
      if (!global.isEmpty() && !loadPackages.contains(global)) {
        packageDir(global);
        loadPackages.add(global);
      }
      for (String pkg : loadPackages) {
        List<String> names = listFiles(pkg);
        Map<String, String> effective = new HashMap<>();
        // Apply configured common-file precedence within the allowed config sources.
        names.sort(Comparator.comparingInt((String n) -> {
          for (int i = 0; i < discovery.commonFiles.size(); i++) {
            String common = discovery.commonFiles.get(i);
            String stem = common.substring(0, common.length() - 4);
            if (n.equalsIgnoreCase(common) || n.toLowerCase(Locale.ROOT).startsWith(stem.toLowerCase(Locale.ROOT) + "-"))
              return -i;
          }
          return 1;
        }).thenComparing(n -> n));
        for (String filename : names)
          if (!filename.equals(SECRET_FILE)) {
            String text = read(pkg, filename);
            Map<String, String> values = map(parse(text));
            String logical = logicalFile(filename);
            files.put(pkg + "/" + logical, values);
            fingerprint
                .append(pkg)
                .append('/')
                .append(filename)
                .append(':')
                .append(hash(text))
                .append('\n');
            if (logical.equals("config.cnf")) effective.putAll(values);
          }
        Map<String, String> overrides = files.get(pkg + "/config-" + env + ".cnf");
        if (overrides != null) effective.putAll(overrides);
        packages.put(pkg, effective);
        if (names.contains(SECRET_FILE)) {
          String text = read(pkg, SECRET_FILE);
          Map<String, String> stored = map(parse(text));
          secrets.put(pkg, stored);
          fingerprint.append(pkg).append("/secrets:").append(hash(text)).append('\n');
          if (!stored.isEmpty()) key = key();
          Map<String, String> decoded = new HashMap<>();
          for (Map.Entry<String, String> e : stored.entrySet()) {
            String value = decrypt(key, pkg, e.getKey(), e.getValue());
            if (cacheClear) decoded.put(e.getKey(), value);
          }
          if (cacheClear) clear.put(pkg, decoded);
        }
        for (Map<String, String> values : Arrays.asList(effective))
          for (String value : values.values()) {
            String ref = handle(value);
            // Missing handles are entered manually through the encrypted-values workflow.
            // They must not block ordinary configuration; plaintext lookup still fails closed.
            // Malformed legacy references are shown in the encrypted inventory for correction.
            // Reject them only when that value is requested, so other packages remain usable.
          }
      }
      Snapshot next =
          new Snapshot(
              env, hash(fingerprint.toString()), global, packages, files, secrets, clear, key);
      active = next;
      return next;
    }

    public Snapshot snapshot() throws ConfigException {
      Snapshot s = active;
      if (s == null) throw new ConfigException("Cache not initialized");
      return s;
    }

    /**
     * Memory-only lookup. No synchronized block, filesystem call, regex on ordinary values, or
     * network call.
     */
    public String lookup(
        Snapshot s,
        String pkg,
        String key,
        String configFile,
        boolean ignoreGlobal,
        boolean scanAll,
        boolean showEncrypted)
        throws Exception {
      if (key == null || key.isEmpty()) throw new ConfigException("key is required");
      String selected = configFile == null ? null : logicalFile(configFile);
      if (selected != null && !selected.isBlank()) {
        if (!selected.matches("config(?:-[A-Za-z0-9_]+)?\\.cnf"))
          throw new ConfigException("configFile accepts a supported basename only");
      } else selected = null;
      String owner = pkg, value = null;
      if (pkg != null && !pkg.isBlank())
        value =
            (selected == null
                    ? s.packages.getOrDefault(pkg, Collections.emptyMap())
                    : s.files.getOrDefault(pkg + "/" + selected, Collections.emptyMap()))
                .get(key);
      if (value == null && !ignoreGlobal && !s.globalPackage.isEmpty()) {
        owner = s.globalPackage;
        value = s.packages.getOrDefault(owner, Collections.emptyMap()).get(key);
      }
      if (value == null && scanAll) {
        owner = null;
        for (String candidate : new TreeSet<>(s.packages.keySet())) {
          String possible = s.packages.get(candidate).get(key);
          if (possible != null) {
            if (owner != null) throw new ConfigException("Ambiguous key across packages");
            owner = candidate;
            value = possible;
          }
        }
      }
      if (value == null) throw new MissingKey();
      if (value.contains("[[encrypted:") && handle(value) == null)
        throw new ConfigException("Embedded/malformed secret reference in package " + owner);
      if (!value.startsWith("[[encrypted:")) return value;
      String ref = handle(value);
      if (ref == null) throw new ConfigException("Malformed encrypted reference");
      if (!showEncrypted) return value;
      String clear = s.clearSecrets.getOrDefault(owner, Collections.emptyMap()).get(ref);
      if (clear != null) return clear;
      String encrypted = s.secrets.getOrDefault(owner, Collections.emptyMap()).get(ref);
      if (encrypted == null) throw new ConfigException("Encrypted handle missing");
      return decrypt(s.key, owner, ref, encrypted);
    }

    /** One all-package inventory, built once per immutable snapshot without any filesystem calls. */
    public String allSecretMetadata() throws Exception {
      Snapshot current = snapshot();
      if (current.encryptedInventory != null) return current.encryptedInventory;
      synchronized (current) {
        if (current.encryptedInventory != null) return current.encryptedInventory;
        List<String> rows = new ArrayList<>();
        for (String pkg : new TreeSet<>(current.packages.keySet())) {
          if (!pkg.startsWith(discovery.prefix)) continue;
          Map<String, Set<String>> references = new TreeMap<>();
          for (Map.Entry<String, Map<String, String>> source : current.files.entrySet()) {
            if (!source.getKey().startsWith(pkg + "/")) continue;
            for (Map.Entry<String, String> property : source.getValue().entrySet()) {
              String ref = handle(property.getValue());
              String location = source.getKey().substring(pkg.length() + 1) + ": " + property.getKey();
              if (ref != null) references.computeIfAbsent(ref, ignored -> new TreeSet<>()).add(location);
              else if (property.getValue().contains("[[encrypted:"))
                rows.add("{\"packageName\":" + q(pkg) + ",\"malformed\":true,\"keys\":" + array(Arrays.asList(location)) + "}");
            }
          }
          Map<String, String> stored = current.secrets.getOrDefault(pkg, Collections.emptyMap());
          Set<String> handles = new TreeSet<>(stored.keySet());
          handles.addAll(references.keySet());
          for (String ref : handles)
            rows.add("{\"packageName\":" + q(pkg) + ",\"handle\":" + q(ref)
                + ",\"configured\":" + stored.containsKey(ref)
                + ",\"keys\":" + array(references.getOrDefault(ref, Collections.emptySet())) + "}");
        }
        current.encryptedInventory = "{\"entries\":[" + String.join(",", rows)
            + "],\"configurationVersion\":" + q(current.version) + "}";
        return current.encryptedInventory;
      }
    }

    /** Read the selected store version only when the administrator begins an edit. */
    public String secretEditInfo(String pkg) throws Exception {
      Path path = file(pkg, SECRET_FILE);
      return "{\"version\":" + q(Files.exists(path) ? hash(readPath(path)) : "NEW") + "}";
    }

    public String secretMetadata(String pkg) throws Exception {
      packageDir(pkg);
      Path secretPath = file(pkg, SECRET_FILE);
      String secretText = Files.exists(secretPath) ? readPath(secretPath) : null;
      Map<String, String> stored = secretText == null ? Collections.emptyMap() : map(parse(secretText));
      String version = secretText == null ? "NEW" : hash(secretText);
      Map<String, Set<String>> referenced = new TreeMap<>();
      List<String> malformed = new ArrayList<>();
      for (String filename : listFiles(pkg)) {
        if (filename.equals(SECRET_FILE)) continue;
        for (Map.Entry<String, String> e : map(parse(read(pkg, filename))).entrySet()) {
          String ref = handle(e.getValue());
          if (ref != null) referenced.computeIfAbsent(ref, ignored -> new TreeSet<>()).add(filename + ": " + e.getKey());
          else if (e.getValue().contains("[[encrypted:")) malformed.add(filename + ": " + e.getKey());
        }
      }
      Set<String> handles = new TreeSet<>(stored.keySet());
      handles.addAll(referenced.keySet());
      List<String> rows = new ArrayList<>();
      for (String ref : handles)
        rows.add("{\"handle\":" + q(ref) + ",\"configured\":" + stored.containsKey(ref)
            + ",\"version\":" + q(version) + ",\"keys\":"
            + array(referenced.getOrDefault(ref, Collections.emptySet())) + "}");
      return "{\"handles\":" + array(handles) + ",\"entries\":[" + String.join(",", rows)
          + "],\"malformed\":" + array(malformed) + "}";
    }

    /** Edit only the encrypted store. Existing common/environment files and comments stay intact. */
    public synchronized void saveHandle(String pkg, String ref, String plain, String expected) throws Exception {
      if (ref == null || ref.isBlank() || ref.contains("]") || ref.contains("\n") || ref.contains("\r") || plain == null)
        throw new ConfigException("Supply a valid secret handle and value");
      Path path = file(pkg, SECRET_FILE);
      String text = Files.exists(path) ? readPath(path) : null;
      if (!Objects.equals(expected, text == null ? "NEW" : hash(text)))
        throw new ConfigException("Encrypted store changed; refresh and reopen the encrypted entry");
      Properties values = text == null ? new Properties() : parse(text);
      values.setProperty(ref, encrypt(pkg, ref, plain));
      writeBatch(Collections.singletonMap(path, serialize(values)));
    }

    /** Retained signature; legacy file reads and service invocation are disabled. */
    public synchronized MigrationResult migrate(
        String sourceService,
        String packagePrefix,
        boolean dryRun,
        String expectedPlan,
        boolean activate)
        throws Exception {
      throw new ConfigException("WxConfig import is disabled; manage config.cnf directly");
    }
  }

  public static final class MigrationResult {
    public final String planId, status, json;

    MigrationResult(String plan, String status, String json) {
      planId = plan;
      this.status = status;
      this.json = json;
    }
  }

  /** Enumerate all effective keys and cached source details for one package. */
  public static void getValuesForPackage(IData pipeline) throws ServiceException {
    try {
      authorize(System.getProperty("config.runtimeAcl", "Administrators"));
      String pkg = input(pipeline, "packageName");
      String alias = input(pipeline, "showPkg");
      pkg = pkg == null ? "" : pkg.trim();
      alias = alias == null ? "" : alias.trim();
      if (pkg.isEmpty()) pkg = alias;
      else if (!alias.isEmpty() && !pkg.equals(alias))
        throw new ConfigException("packageName and showPkg must identify the same package");
      if (pkg.isEmpty()) throw new ConfigException("packageName is required");
      if (!pkg.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || pkg.contains(".."))
        throw new ConfigException("Invalid package name");
      boolean includeGlobal = flag(input(pipeline, "includeGlobalValues"), false);
      boolean reveal = flag(input(pipeline, "showEncryptedValues"), false);
      String[] ignored;
      IDataCursor in = pipeline.getCursor();
      try {
        ignored = IDataUtil.getStringArray(in, "ignoreKeysStartingWith");
      } finally {
        in.destroy();
      }

      // Capture exactly one immutable version. No directory scan or file read on this path
      // after the existing store has initialized at startup (or its first invocation).
      ConfigStore s = store();
      ConfigStore.Snapshot snap = s.snapshot();
      Map<String, String> local = snap.packages.get(pkg);
      if (local == null)
        throw new ConfigException("Package is not loaded in the configuration snapshot");
      Map<String, String> global = includeGlobal
          ? snap.packages.getOrDefault(snap.globalPackage, Collections.emptyMap())
          : Collections.emptyMap();
      SortedSet<String> keys = new TreeSet<>(local.keySet());
      keys.addAll(global.keySet());

      // Cache the selected owners' logical source maps once for this response.
      // Snapshot filenames follow the same normalization as existing getValue lookups.
      Map<String, SortedMap<String, Map<String, String>>> sources = new HashMap<>();
      sources.put(pkg, new TreeMap<>());
      if (includeGlobal && !snap.globalPackage.isEmpty())
        sources.putIfAbsent(snap.globalPackage, new TreeMap<>());
      for (Map.Entry<String, Map<String, String>> file : snap.files.entrySet()) {
        int slash = file.getKey().indexOf('/');
        if (slash < 0) continue;
        SortedMap<String, Map<String, String>> selected =
            sources.get(file.getKey().substring(0, slash));
        if (selected != null) selected.put(file.getKey().substring(slash + 1), file.getValue());
      }

      List<IData> contents = new ArrayList<>();
      List<IData> keyValues = new ArrayList<>();
      String environmentFile = "config-" + snap.environment + ".cnf";
      for (String key : keys) {
        boolean skip = false;
        if (ignored != null)
          for (String prefix : ignored)
            if (prefix != null && !prefix.isEmpty() && key.startsWith(prefix)) {
              skip = true;
              break;
            }
        if (skip) continue;
        String owner = local.containsKey(key) ? pkg : snap.globalPackage;
        String raw = snap.packages.get(owner).get(key);
        // A diagnostic listing must preserve malformed/missing markers by default.
        // Explicit plaintext requests use the existing fail-closed secret resolver.
        String value = reveal && raw.contains("[[encrypted:")
            ? s.lookup(snap, owner, key, null, true, false, true) : raw;
        SortedMap<String, Map<String, String>> ownerFiles = sources.get(owner);
        Map<String, String> base = ownerFiles.getOrDefault("config.cnf", Collections.emptyMap());
        Map<String, String> overrides = ownerFiles.getOrDefault(environmentFile, Collections.emptyMap());
        String winningFile = overrides.containsKey(key) ? environmentFile
            : base.containsKey(key) ? "config.cnf" : "";
        List<IData> fileRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> file : ownerFiles.entrySet()) {
          if (!file.getValue().containsKey(key)) continue;
          IData source = IDataFactory.create();
          output(source, "fileName", file.getKey());
          // Source values describe the stored property, so encrypted references stay references.
          output(source, "value", file.getValue().get(key));
          output(source, "isActive", Boolean.toString(file.getKey().equals("config.cnf")
              || file.getKey().equals(environmentFile)));
          output(source, "isEffective", Boolean.toString(file.getKey().equals(winningFile)));
          fileRows.add(source);
        }

        IData row = IDataFactory.create();
        output(row, "package", owner);
        output(row, "key", key);
        output(row, "value", value);
        // The current properties store has scalar values. Do not split commas/newlines.
        output(row, "valueList", new String[] {value});
        // This engine does not evaluate ${...}; retained expressions remain unresolved.
        output(row, "variable", Boolean.toString(raw.contains("${")));
        output(row, "isGlobalValue", Boolean.toString(!snap.globalPackage.isEmpty()
            && owner.equals(snap.globalPackage)));
        output(row, "containsUnresolvedInterpolator", Boolean.toString(raw.contains("${")));
        output(row, "files", fileRows.toArray(new IData[0]));
        contents.add(row);
        IData pair = IDataFactory.create();
        output(pair, "key", key);
        output(pair, "value", value);
        keyValues.add(pair);
      }

      // Publish complete, detached response documents only after every value succeeds.
      output(pipeline, "packageName", pkg);
      output(pipeline, "contents", contents.toArray(new IData[0]));
      output(pipeline, "keyValues", keyValues.toArray(new IData[0]));
      output(pipeline, "totalCount", Integer.toString(contents.size()));
      output(pipeline, "environment", snap.environment);
      output(pipeline, "configurationVersion", snap.version);
    } catch (Exception e) {
      throw failure(e);
    }
  }
}
