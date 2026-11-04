import com.wm.app.b2b.server.*;
import com.wm.data.*;
import config.services;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Behavior checks with simulated IS APIs; not a live Integration Server deployment. */
public class ConfigPackageValuesTest {
  static int count;
  static Path root;
  interface Work { void run() throws Exception; }
  static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    count++;
    System.out.println("PASS " + label);
  }
  static void rejects(Work action, String label) throws Exception {
    try { action.run(); } catch (Exception expected) { check(true, label); return; }
    throw new AssertionError(label);
  }
  static IData p(String... args) {
    IData data = IDataFactory.create();
    for (int i = 0; i < args.length; i += 2) put(data, args[i], args[i + 1]);
    return data;
  }
  static void put(IData data, String key, Object value) {
    IDataCursor cursor = data.getCursor();
    try { IDataUtil.put(cursor, key, value); } finally { cursor.destroy(); }
  }
  static Object field(IData data, String key) {
    // values() belongs only to this test fixture. Production uses supported IDataUtil methods.
    IDataCursor cursor = data.getCursor();
    try { return cursor.values().get(key); } finally { cursor.destroy(); }
  }
  static String text(IData data, String key) { return (String) field(data, key); }
  static IData[] rows(IData data, String key) { return (IData[]) field(data, key); }
  static IData row(IData data, String key) {
    for (IData candidate : rows(data, "contents"))
      if (key.equals(text(candidate, "key"))) return candidate;
    return null;
  }
  static IData source(IData row, String filename) {
    for (IData candidate : rows(row, "files"))
      if (filename.equals(text(candidate, "fileName"))) return candidate;
    return null;
  }
  static void pkg(String name) throws Exception {
    Files.createDirectories(root.resolve(name + "/config"));
    Files.writeString(root.resolve(name + "/manifest.v3"), "test");
  }
  static void write(String pkg, String name, String contents) throws Exception {
    Files.writeString(root.resolve(pkg + "/config/" + name), contents);
  }
  static IData list(String pkg, String... flags) throws Exception {
    IData input = p("packageName", pkg);
    for (int i = 0; i < flags.length; i += 2) put(input, flags[i], flags[i + 1]);
    services.getValuesForPackage(input);
    return input;
  }
  static void reload() throws Exception {
    IData token = p(); services.getAdminToken(token);
    services.resetServerCache(p("token", text(token, "token")));
  }

  public static void main(String[] args) throws Exception {
    root = Files.createTempDirectory("package-values-");
    for (String name : Arrays.asList("config", "NBKDemo", "NBKEmpty", "NBKSecret", "NBKMissing",
        "NBKMalformed", "NBKBig", "NBKConcurrent", "NBKCase", "OtherPackage")) pkg(name);
    Path key = Files.createTempFile("package-values-master-", ".key");
    Files.writeString(key, Base64.getEncoder().encodeToString(new byte[32]));
    write("config", "manager.properties", "environment=dev\nglobalPackage=config\nmasterKeyFile="
        + key + "\ncommonConfigFiles=wxConfig.cnf,config.cnf\n");
    write("config", "config.cnf", "globalOnly=global\ntimeout=global-timeout\n");
    write("NBKDemo", "config.cnf", "timeout=30000\nmessage=base\nempty=\ntamil=வணக்கம்\n"
        + "csv=a,b,c\nexpression=${env:SAMPLE}\ninternal.hidden=x\n");
    write("NBKDemo", "config-dev.cnf", "message=development\n");
    write("NBKDemo", "config-uat.cnf", "message=uat-only\nonlyUat=not-active\n");
    write("NBKDemo", "wxConfig.cnf", "legacy.only=must-not-load\nbad=\\uZZZZ\n");
    write("NBKMissing", "config.cnf", "password=[[encrypted:missing]]\nnormal=ok\n");
    write("NBKMalformed", "config.cnf", "password=prefix[[encrypted:bad]]\nnormal=ok\n");
    write("NBKSecret", "config.cnf", "");
    write("NBKConcurrent", "config.cnf", "left=one\nright=one\n");
    write("NBKCase", "config.cnf", "upper=folder-value\n");
    Files.move(root.resolve("NBKCase/config"), root.resolve("NBKCase/Config"));
    write("OtherPackage", "config.cnf", "excluded=yes\n");
    StringBuilder many = new StringBuilder();
    for (int i = 0; i < 1501; i++) many.append(String.format("key%04d=value%d\n", i, i));
    write("NBKBig", "config.cnf", many.toString());
    services.ConfigStore preparation = new services.ConfigStore(root);
    preparation.reset();
    preparation.saveSecret("NBKSecret", "config.cnf", "password", "local_handle", "secret-test-only",
        services.ConfigStore.hash(""));
    preparation.saveSecret("config", "config.cnf", "sharedPassword", "global_handle", "global-test-only",
        services.ConfigStore.hash(preparation.read("config", "config.cnf")));
    System.setProperty("config.packagesRoot", root.toString());
    services.startup(p());

    IData all = list("NBKDemo");
    check(rows(all, "contents").length == 7, "package-name-only request enumerates every active key");
    check("7".equals(text(all, "totalCount")), "totalCount equals contents length");
    check("NBKDemo".equals(text(all, "packageName")), "packageName returned for wrapper mapping");
    check("dev".equals(text(all, "environment")), "active environment returned");
    check(text(all, "configurationVersion") != null, "snapshot version returned");
    check("development".equals(text(row(all, "message"), "value")), "environment override is effective");
    check(row(all, "onlyUat") == null, "inactive-only keys do not enter effective key results");
    check(row(all, "globalOnly") == null, "global keys are excluded by default");
    check(row(all, "legacy.only") == null && Service.legacyCalls == 0, "no legacy file or service is read");
    check("".equals(text(row(all, "empty"), "value")), "empty string values preserved");
    check("வணக்கம்".equals(text(row(all, "tamil"), "value")), "Unicode values preserved");
    String[] csv = (String[]) field(row(all, "csv"), "valueList");
    check(csv.length == 1 && "a,b,c".equals(csv[0]), "valueList is a String List without splitting values");
    check("true".equals(text(row(all, "expression"), "variable"))
        && "true".equals(text(row(all, "expression"), "containsUnresolvedInterpolator"))
        && "${env:SAMPLE}".equals(text(row(all, "expression"), "value")),
        "interpolation tokens flagged and kept literal");
    check("false".equals(text(row(all, "timeout"), "variable")), "ordinary value has no variable flag");
    IData message = row(all, "message");
    check(rows(message, "files").length == 3, "per-key file details include inactive definitions");
    check("true".equals(text(source(message, "config-dev.cnf"), "isEffective")),
        "winning environment source identified");
    check("true".equals(text(source(message, "config.cnf"), "isActive"))
        && "false".equals(text(source(message, "config.cnf"), "isEffective")),
        "overridden base source stays active but is not effective");
    check("false".equals(text(source(message, "config-uat.cnf"), "isActive")),
        "inactive environment source identified");
    check("base".equals(text(source(message, "config.cnf"), "value")), "raw source values retained");
    check("true".equals(text(source(row(all, "timeout"), "config.cnf"), "isEffective")),
        "base source wins when there is no environment override");
    String previous = "";
    for (IData entry : rows(all, "contents")) {
      if (text(entry, "key").compareTo(previous) < 0) throw new AssertionError("unsorted");
      previous = text(entry, "key");
    }
    check(true, "keys returned in deterministic order");
    check(rows(all, "keyValues").length == rows(all, "contents").length,
        "simple wrapper keyValues has one pair per contents row");
    for (int i = 0; i < rows(all, "keyValues").length; i++) {
      IData pair = rows(all, "keyValues")[i], detail = rows(all, "contents")[i];
      if (!text(pair, "key").equals(text(detail, "key"))
          || !text(pair, "value").equals(text(detail, "value"))) throw new AssertionError("pair mismatch");
    }
    check(true, "wrapper pairs use the same effective values");
    IData alias = p("showPkg", "NBKDemo"); services.getValuesForPackage(alias);
    check(rows(alias, "contents").length == 7, "showPkg alias supports screenshot input naming");
    IData both = p("showPkg", "NBKDemo", "packageName", "NBKDemo"); services.getValuesForPackage(both);
    check(rows(both, "contents").length == 7, "matching primary and alias inputs accepted");
    check("NBKDemo".equals(text(list(" NBKDemo "), "packageName")), "surrounding package-name whitespace trimmed");
    rejects(() -> services.getValuesForPackage(p("packageName", "NBKDemo", "showPkg", "NBKSecret")),
        "conflicting package-name inputs rejected");
    IData filtered = p("packageName", "NBKDemo");
    put(filtered, "ignoreKeysStartingWith", new String[] {"internal.", "expression", "", null});
    services.getValuesForPackage(filtered);
    check(row(filtered, "internal.hidden") == null && row(filtered, "expression") == null
        && rows(filtered, "contents").length == 5, "prefix list filters keys and ignores blank prefixes");
    check(rows(list("NBKEmpty"), "contents").length == 0, "known empty package returns empty document lists");
    IData inherited = list("NBKDemo", "includeGlobalValues", "true");
    check("global".equals(text(row(inherited, "globalOnly"), "value")), "global inclusion is explicit");
    check("config".equals(text(row(inherited, "globalOnly"), "package"))
        && "true".equals(text(row(inherited, "globalOnly"), "isGlobalValue")), "global ownership marked");
    check("30000".equals(text(row(inherited, "timeout"), "value"))
        && "false".equals(text(row(inherited, "timeout"), "isGlobalValue")), "local keys override global collisions");
    check(rows(list("config", "includeGlobalValues", "true"), "contents").length == 3,
        "direct global-package request has no duplicate keys");
    IData secret = list("NBKSecret");
    check("[[encrypted:local_handle]]".equals(text(row(secret, "password"), "value")),
        "encrypted values return references by default");
    IData revealed = list("NBKSecret", "showEncryptedValues", "true");
    check("secret-test-only".equals(text(row(revealed, "password"), "value")), "explicit reveal resolves local secret");
    check("[[encrypted:local_handle]]".equals(text(source(row(revealed, "password"), "config.cnf"), "value")),
        "source details preserve stored encrypted marker even when effective value is revealed");
    IData globalSecret = list("NBKEmpty", "includeGlobalValues", "true", "showEncryptedValues", "true");
    check("global-test-only".equals(text(row(globalSecret, "sharedPassword"), "value")),
        "global secret resolves using its owning package");
    check("[[encrypted:missing]]".equals(text(row(list("NBKMissing"), "password"), "value")),
        "missing secret remains listable as a reference");
    rejects(() -> list("NBKMissing", "showEncryptedValues", "true"), "missing plaintext secret fails closed");
    check(row(list("NBKMalformed"), "normal") != null && row(list("NBKMalformed"), "password") != null,
        "malformed secret is diagnostic data in default listing");
    rejects(() -> list("NBKMalformed", "showEncryptedValues", "true"), "malformed plaintext request fails closed");
    check(rows(list("NBKBig"), "contents").length == 1501, "enumeration is not limited by existing 1000-key bulk API");
    check("folder-value".equals(text(row(list("NBKCase"), "upper"), "value")), "uppercase Config folder supported");
    rejects(() -> services.getValuesForPackage(p()), "missing package name rejected");
    rejects(() -> list("../NBKDemo"), "package traversal rejected");
    rejects(() -> list("NotInstalled"), "unknown package rejected even when globals exist");
    rejects(() -> list("OtherPackage"), "package outside configured load prefix rejected");
    rejects(() -> list("NBKDemo", "includeGlobalValues", "yes"), "invalid global flag rejected");
    rejects(() -> list("NBKDemo", "showEncryptedValues", "yes"), "invalid reveal flag rejected");
    Service.user = new User("Anonymous");
    rejects(() -> list("NBKDemo"), "anonymous caller denied");
    Service.user = new User("Administrator");

    put(row(all, "timeout"), "value", "tampered");
    ((String[]) field(row(all, "csv"), "valueList"))[0] = "tampered";
    put(source(row(all, "message"), "config.cnf"), "value", "tampered");
    IData fresh = list("NBKDemo");
    check("30000".equals(text(row(fresh, "timeout"), "value"))
        && "a,b,c".equals(((String[]) field(row(fresh, "csv"), "valueList"))[0])
        && "base".equals(text(source(row(fresh, "message"), "config.cnf"), "value")),
        "returned documents and arrays cannot mutate shared snapshot");
    String stored = Files.readString(root.resolve("NBKDemo/config/config.cnf"));
    write("NBKDemo", "config.cnf", stored.replace("timeout=30000", "timeout=60000"));
    check("30000".equals(text(row(list("NBKDemo"), "timeout"), "value")), "saved disk changes await reload");
    String oldVersion = text(fresh, "configurationVersion");
    reload();
    IData changed = list("NBKDemo");
    check("60000".equals(text(row(changed, "timeout"), "value"))
        && !oldVersion.equals(text(changed, "configurationVersion")), "reload atomically activates values and version");

    ExecutorService pool = Executors.newFixedThreadPool(3);
    List<Future<?>> readers = new ArrayList<>();
    for (int thread = 0; thread < 3; thread++) readers.add(pool.submit(() -> {
      try {
        for (int j = 0; j < 1000; j++) {
          IData result = list("NBKConcurrent");
          if (!text(row(result, "left"), "value").equals(text(row(result, "right"), "value")))
            throw new AssertionError("mixed versions");
          if (!text(source(row(result, "left"), "config.cnf"), "value")
              .equals(text(row(result, "left"), "value"))) throw new AssertionError("mixed source versions");
        }
      } catch (Exception e) { throw new RuntimeException(e); }
    }));
    try {
      for (int i = 0; i < 6; i++) {
        String val = i % 2 == 0 ? "two" : "one";
        write("NBKConcurrent", "config.cnf", "left=" + val + "\nright=" + val + "\n");
        reload();
      }
      for (Future<?> reader : readers) reader.get();
    } finally { pool.shutdown(); }
    check(true, "concurrent enumeration keeps values and sources in one snapshot");
    Path offline = root.resolveSibling(root.getFileName() + "-offline");
    Files.move(root, offline);
    try {
      IData memory = list("NBKDemo");
      IData clear = list("NBKSecret", "showEncryptedValues", "true");
      check("60000".equals(text(row(memory, "timeout"), "value"))
          && rows(row(memory, "message"), "files").length == 3,
          "all values and file details remain available with package tree offline");
      check("secret-test-only".equals(text(row(clear, "password"), "value")),
          "secret reveal also uses captured snapshot without filesystem access");
    } finally { Files.move(offline, root); }
    check(Service.legacyCalls == 0, "all enumeration scenarios invoke no legacy services");
    System.out.println("TOTAL " + count + " package-value checks passed");
  }
}
