import com.wm.app.b2b.server.*;
import com.wm.data.*;
import config.services;
import config.services.ConfigStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Executable behavior tests. IS APIs are simulated; this is not an actual IS deployment test. */
public class ConfigFullTest {
  static int count;

  interface Work {
    void run() throws Exception;
  }

  static void check(boolean b, String label) {
    if (!b) throw new AssertionError(label);
    count++;
    System.out.println("PASS " + label);
  }

  static void rejects(Work work, String label) throws Exception {
    try {
      work.run();
    } catch (Exception e) {
      check(true, label);
      return;
    }
    throw new AssertionError(label);
  }

  static IData p(String... args) {
    IData v = IDataFactory.create();
    for (int i = 0; i < args.length; i += 2) IDataUtil.put(v.getCursor(), args[i], args[i + 1]);
    return v;
  }

  static String out(IData p, String name) {
    return IDataUtil.getString(p.getCursor(), name);
  }

  static Path root;

  static void pkg(String name) throws Exception {
    Files.createDirectories(root.resolve(name + "/config"));
    Files.writeString(root.resolve(name + "/manifest.v3"), "test");
  }

  static void write(String pkg, String file, String text) throws Exception {
    Files.writeString(root.resolve(pkg + "/config/" + file), text);
  }

  static String get(ConfigStore s, String pkg, String key, boolean reveal) throws Exception {
    return s.lookup(s.snapshot(), pkg, key, null, false, false, reveal);
  }

  public static void main(String[] args) throws Exception {
    root = Files.createTempDirectory("config-full-");
    for (String name : Arrays.asList("config", "NBKDemo", "NBKLegacy", "NBKOther")) pkg(name);
    write(
        "config",
        "manager.properties",
        "environment=dev\nglobalPackage=config\ncacheDecryptedSecrets=false\ncommonConfigFiles=wxConfig.cnf,config.cnf\n");
    Path fixtureKey = Files.createTempFile("config-test-master-", ".key");
    Files.writeString(fixtureKey, Base64.getEncoder().encodeToString(new byte[32]));
    Files.writeString(root.resolve("config/config/manager.properties"), "masterKeyFile=" + fixtureKey.toString() + "\n", StandardOpenOption.APPEND);
    write("config", "config.cnf", "globalKey=global\n");
    write("NBKDemo", "config.cnf", "timeout=30000\nmessage=common\ntamil=வணக்கம்\na=one\nb=one\n");
    write("NBKDemo", "config-dev.cnf", "message=dev\n");
    pkg("OtherPackage");
    write("OtherPackage", "config.cnf", "bad=\\uZZZZ");
    pkg("NBKExisting");
    write("NBKExisting", "config.cnf", "existing.timeout=70000\n");
    pkg("NBKCasePath");
    Files.move(root.resolve("NBKCasePath/config"), root.resolve("NBKCasePath/Config"));
    Files.writeString(root.resolve("NBKCasePath/Config/config.cnf"), "case.timeout=70000\n");
    Files.writeString(root.resolve("NBKCasePath/Config/wxConfig.cnf"), "case.timeout=99999\nlegacy.only=hidden\nbad=\\uZZZZ\n");
    Files.writeString(root.resolve("NBKCasePath/Config/wxConfig-dev.cnf"), "case.timeout=88888\nbad=\\uZZZZ\n");
    pkg("NBKLegacyOnly");
    write("NBKLegacyOnly", "wxconfig.cnf", "legacy.only=hidden\n");
    write("NBKLegacyOnly", "wxconfig-dev.cnf", "legacy.stage=hidden\n");
    ConfigStore s = new ConfigStore(root);
    s.reset();
    check(s.listFiles("NBKCasePath").equals(Arrays.asList("config.cnf")), "DSP lists config.cnf without legacy files");
    check(get(s, "NBKCasePath", "case.timeout", false).equals("70000"), "Config/config.cnf loads while malformed legacy files are ignored");
    check(s.lookup(s.snapshot(), "NBKCasePath", "case.timeout", "config.cnf", false, false, false).equals("70000"), "explicit config.cnf lookup resolves");
    check(!s.discoveryJson().toLowerCase(Locale.ROOT).contains("wxconfig"), "old manager settings cannot enable legacy sources");
    check(s.listFiles("NBKLegacyOnly").isEmpty(), "legacy-only package has no supported files");
    rejects(() -> get(s, "NBKLegacyOnly", "legacy.only", false), "legacy-only keys do not enter JVM cache");
    rejects(() -> s.read("NBKCasePath", "wxConfig.cnf"), "direct legacy read rejected");
    rejects(() -> s.save("NBKCasePath", "wxConfig.cnf", "case.timeout=1", "NEW"), "direct legacy write rejected");
    check(s.snapshot().files.keySet().stream().noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("wxconfig")), "snapshot contains no legacy source files");
    String cleanVersion = s.snapshot().version;
    Files.writeString(root.resolve("NBKCasePath/Config/wxConfig.cnf"), "case.timeout=77777\nbad=\\uZZZZ\n");
    s.reset();
    check(s.snapshot().version.equals(cleanVersion), "legacy file edits do not change configuration version");
    String caseText = s.read("NBKCasePath", "config.cnf");
    s.save("NBKCasePath", "config.cnf", "case.timeout=80000\n", ConfigStore.hash(caseText));
    s.reset();
    check(get(s, "NBKCasePath", "case.timeout", false).equals("80000"), "save and reload use config.cnf in uppercase Config folder");
    check(s.listPackages().contains("NBKExisting") && !s.listPackages().contains("OtherPackage") && !s.listPackages().contains("config"), "discovery includes NBK business packages only");
    check(get(s, "NBKExisting", "existing.timeout", false).equals("70000"), "existing NBK config.cnf auto-loaded without migration");
    Files.delete(root.resolve("NBKExisting/config/config.cnf"));
    check(get(s, "NBKExisting", "existing.timeout", false).equals("70000"), "lookup uses JVM snapshot without reading disk");
    write("NBKExisting", "config.cnf", "existing.timeout=80000\n");
    s.reset();
    check(get(s, "NBKExisting", "existing.timeout", false).equals("80000"), "reload activates existing NBK file changes");
    check(get(s, "NBKDemo", "timeout", false).equals("30000"), "startup loads config.cnf");
    check(get(s, "NBKDemo", "message", false).equals("dev"), "environment override wins");
    check(get(s, "NBKDemo", "tamil", false).equals("வணக்கம்"), "UTF-8 preserved");
    check(get(s, "NBKDemo", "globalKey", false).equals("global"), "configured global fallback");
    rejects(
        () -> s.lookup(s.snapshot(), "NBKDemo", "globalKey", null, true, false, false),
        "ignoreGlobalValues suppresses fallback");
    rejects(
        () -> s.lookup(s.snapshot(), "NBKDemo", "message", "wxconfig.cnf", false, false, false),
        "legacy configFile no longer aliases config.cnf");
    String text = s.read("NBKDemo", "config.cnf"), hash = ConfigStore.hash(text);
    s.save("NBKDemo", "config.cnf", text.replace("30000", "60000"), hash);
    check(
        get(s, "NBKDemo", "timeout", false).equals("30000"), "Save leaves active cache unchanged");
    rejects(() -> s.save("NBKDemo", "config.cnf", "timeout=1", hash), "stale save rejected");
    s.reset();
    check(get(s, "NBKDemo", "timeout", false).equals("60000"), "reset activates saved value");
    ConfigStore restarted = new ConfigStore(root);
    restarted.reset();
    check(get(restarted, "NBKDemo", "timeout", false).equals("60000"), "restart loads saved files");
    rejects(() -> s.read("../NBKDemo", "config.cnf"), "package traversal rejected");
    rejects(() -> s.read("NBKDemo", "../../server.cnf"), "file traversal rejected");
    rejects(() -> s.read("NBKDemo", "passman.cnf"), "unrelated file access rejected");
    Path foreign = Files.createTempFile("foreign-", ".cnf");
    Files.createSymbolicLink(root.resolve("NBKDemo/config/config-evil.cnf"), foreign);
    rejects(() -> s.read("NBKDemo", "config-evil.cnf"), "symbolic link rejected");
    text = s.read("NBKDemo", "config.cnf");
    s.saveSecret(
        "NBKDemo",
        "config.cnf",
        "password",
        "original_handle",
        "secret-test-only",
        ConfigStore.hash(text));
    check(
        s.read("NBKDemo", "config.cnf").contains("[[encrypted\\:original_handle]]"),
        "config file keeps encrypted handle syntax");
    check(
        !s.read("NBKDemo", "config.secrets.cnf").contains("secret-test-only"),
        "secret file contains no plaintext");
    s.reset();
    check(
        get(s, "NBKDemo", "password", false).equals("[[encrypted:original_handle]]"),
        "false returns handle reference");
    check(get(s, "NBKDemo", "password", true).equals("secret-test-only"), "true returns plaintext");
    check(s.snapshot().clearSecrets.isEmpty(), "plaintext not cached by default");
    rejects(
        () -> s.save("NBKDemo", "config.secrets.cnf", "oops=plaintext", "NEW"),
        "secret store rejects plaintext");
    String before = s.snapshot().version;
    write("NBKDemo", "config-dev.cnf", "bad=\\uZZZZ");
    rejects(() -> s.reset(), "bad config rejects reset");
    check(s.snapshot().version.equals(before), "failed reset retains old snapshot");
    write("NBKDemo", "config-dev.cnf", "message=dev\n");
    write("NBKLegacy", "wxconfig.cnf", "legacy.only=hidden\nbad=\\uZZZZ\n");
    write("NBKLegacy", "config.cnf", "url=https://example.test\n");
    s.saveSecret("NBKLegacy", "config.cnf", "password", "local_handle", "local-secret-only",
        ConfigStore.hash(s.read("NBKLegacy", "config.cnf")));
    s.reset();
    Service.legacyCalls = 0;
    String legacyBefore = Files.readString(root.resolve("NBKLegacy/config/wxconfig.cnf"));
    rejects(() -> s.migrate("legacy.config:getValue", "NBKLegacy", true, null, true),
        "legacy import dry run is disabled");
    rejects(() -> s.migrate("legacy.config:getValue", "NBKLegacy", false, "unused", true),
        "legacy import apply is disabled");
    check(Service.legacyCalls == 0, "disabled import never invokes a legacy service");
    check(Files.readString(root.resolve("NBKLegacy/config/wxconfig.cnf")).equals(legacyBefore),
        "legacy file remains untouched");
    check(get(s, "NBKLegacy", "password", true).equals("local-secret-only"),
        "manually added config secret resolves locally");
    System.setProperty("config.packagesRoot", root.toString());
    IData startup = p();
    services.startup(startup);
    IData value = p("wxConfigPkgName", "NBKLegacy", "key", "url");
    services.getValue(value);
    check(
        out(value, "propertyValue").equals("https://example.test"),
        "wrapper input/output contract");
    IData secret =
        p("wxConfigPkgName", "NBKLegacy", "key", "password", "showEncryptedValues", "true");
    services.getValue(secret);
    check(out(secret, "propertyValue").equals("local-secret-only"), "service plaintext flag");
    IData missing =
        p("wxConfigPkgName", "NBKLegacy", "key", "absent", "noServiceException", "true");
    services.getValue(missing);
    check(out(missing, "propertyValue") == null, "missing suppression returns null");
    rejects(
        () -> services.getValue(p("wxConfigPkgName", "NBKLegacy", "key", "absent")),
        "missing default throws");
    rejects(
        () ->
            services.getValue(
                p("wxConfigPkgName", "NBKLegacy", "key", "url", "substituteVariables", "true")),
        "excluded interpolation flag rejected");
    IData admin = p();
    services.getAdminToken(admin);
    String token = out(admin, "token");
    rejects(() -> services.migrate(p("token", token, "sourceService", "legacy.config:getValue")),
        "public migration service is disabled");
    IData migration = p("action", "migrate", "token", token, "sourceService", "legacy.config:getValue");
    services.dispatch(migration);
    check(out(migration, "result").contains("disabled"), "DSP migration action reports disabled");
    check(Service.legacyCalls == 0, "service and DSP routes never invoke legacy getValue");
    IData legacyRead = p("action", "read", "packageName", "NBKLegacy", "fileName", "wxconfig.cnf");
    services.dispatch(legacyRead);
    check(out(legacyRead, "result").contains("error") && !out(legacyRead, "result").contains("hidden"),
        "DSP cannot read a legacy file directly");
    rejects(() -> services.resetServerCache(p()), "reset without token denied");
    rejects(() -> services.resetServerCache(p("token", token + "bad")), "tampered token denied");
    Service.user = new User("Anonymous");
    rejects(
        () -> services.getValue(p("key", "url", "wxConfigPkgName", "NBKLegacy")),
        "anonymous runtime denied");
    rejects(() -> services.dispatch(p("action", "status")), "anonymous admin denied");
    Service.user = new User("Administrator");
    IData bulk = p("wxConfigPkgName", "NBKDemo");
    IDataUtil.put(bulk.getCursor(), "keys", new String[] {"a", "b"});
    services.getValues(bulk);
    IData returned = (IData) bulk.getCursor().values().get("values");
    IDataUtil.put(returned.getCursor(), "a", "mutated");
    IData single = p("wxConfigPkgName", "NBKDemo", "key", "a");
    services.getValue(single);
    check(
        out(single, "propertyValue").equals("one"),
        "bulk response cannot mutate internal snapshot");
    check(out(bulk, "configurationVersion") != null, "bulk returns snapshot version");
    // Repeated readers capture the same immutable snapshot while resets publish other versions.
    ExecutorService pool = Executors.newFixedThreadPool(4);
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      futures.add(
          pool.submit(
              () -> {
                try {
                  for (int j = 0; j < 10000; j++) {
                    ConfigStore.Snapshot snap = s.snapshot();
                    String a = s.lookup(snap, "NBKDemo", "a", null, false, false, false),
                        b = s.lookup(snap, "NBKDemo", "b", null, false, false, false);
                    if (!a.equals(b)) throw new AssertionError("mixed snapshot");
                  }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }));
    for (int i = 0; i < 8; i++) {
      String current = s.read("NBKDemo", "config.cnf");
      String next = current.replace("a=one", "a=two").replace("b=one", "b=two");
      s.save("NBKDemo", "config.cnf", next, ConfigStore.hash(current));
      s.reset();
    }
    for (Future<?> f : futures) f.get();
    pool.shutdown();
    check(true, "concurrent snapshot readers remain consistent during reset");
    write(
        "config",
        "manager.properties",
        "environment=dev\nglobalPackage=config\ncacheDecryptedSecrets=true\n");
    s.reset();
    check(!s.snapshot().clearSecrets.isEmpty(), "explicit decrypted-cache option works");
    check(
        get(s, "NBKLegacy", "password", true).equals("local-secret-only"),
        "decrypted-cache lookup returns locally stored value");

    // Verify the combined action on the same loaded service implementation.
    IData authToken = p();
    services.getAdminToken(authToken);
    String combinedToken = out(authToken, "token");
    String original = Files.readString(root.resolve("NBKDemo/config/config.cnf"));
    Files.writeString(
        root.resolve("NBKDemo/config/config.cnf"),
        original.replace("timeout=60000", "timeout=70000"));
    Service.serviceResetCalls = 0;
    IData combined = p("action", "resetAll", "token", combinedToken);
    services.dispatch(combined);
    check(
        out(combined, "result").contains("\"configurationReloaded\":true"),
        "combined action reports configuration reloaded");
    check(
        out(combined, "result").contains("\"serviceResultsReset\":true")
            && Service.serviceResetCalls == 1,
        "combined action invokes built-in reset once");
    IData fresh = p("wxConfigPkgName", "NBKDemo", "key", "timeout");
    services.getValue(fresh);
    check(
        out(fresh, "propertyValue").equals("70000"), "combined action publishes new configuration");
    Files.writeString(root.resolve("NBKDemo/config/config-dev.cnf"), "bad=\\uZZZZ");
    int callsBefore = Service.serviceResetCalls;
    IData invalid = p("action", "resetAll", "token", combinedToken);
    services.dispatch(invalid);
    check(
        out(invalid, "result").contains("\"error\"") && Service.serviceResetCalls == callsBefore,
        "configuration validation failure skips built-in reset");
    services.getValue(fresh);
    check(
        out(fresh, "propertyValue").equals("70000"),
        "validation failure retains previous active configuration");
    Files.writeString(root.resolve("NBKDemo/config/config-dev.cnf"), "message=dev\n");
    Files.writeString(
        root.resolve("NBKDemo/config/config.cnf"),
        original.replace("timeout=60000", "timeout=80000"));
    Service.failServiceReset = true;
    IData partial = p("action", "resetAll", "token", combinedToken);
    services.dispatch(partial);
    check(
        out(partial, "result").contains("\"configurationReloaded\":true")
            && out(partial, "result").contains("\"serviceResultsReset\":false"),
        "built-in reset failure is reported as partial success");
    services.getValue(fresh);
    check(
        out(fresh, "propertyValue").equals("80000"),
        "partial success preserves successful configuration reload");
    Service.failServiceReset = false;
    int previous = Service.serviceResetCalls;
    services.dispatch(p("action", "reset", "token", combinedToken));
    check(Service.serviceResetCalls == previous, "configuration-only reset remains separate");

    String managerBefore = Files.readString(root.resolve("config/config/manager.properties"));
    pkg("APPDemo");
    Files.move(root.resolve("APPDemo/config"), root.resolve("APPDemo/configuration"));
    Files.writeString(root.resolve("APPDemo/configuration/settings.cnf"), "custom=works\n");
    Files.writeString(root.resolve("APPDemo/configuration/settings-dev.cnf"), "custom=override\n");
    write("config", "manager.properties", "environment=dev\npackagePrefix=APP\nconfigurationDirectories=configuration\ncommonConfigFiles=settings.cnf\n");
    s.reset();
    check(s.listPackages().equals(Arrays.asList("APPDemo")), "configured package prefix replaces NBK filter");
    check(s.listFiles("APPDemo").contains("settings.cnf"), "configured folder and filename discovered");
    check(get(s, "APPDemo", "custom", false).equals("override"), "configured environment override loads");
    check(s.lookup(s.snapshot(), "APPDemo", "custom", "settings.cnf", false, false, false).equals("works"), "configured configFile lookup resolves");
    check(s.discoveryJson().contains("settings.cnf"), "DSP receives configured filenames");
    write("config", "manager.properties", "configurationDirectories=../unsafe\n");
    rejects(() -> s.reset(), "unsafe configurable path rejected");
    check(get(s, "APPDemo", "custom", false).equals("override"), "invalid discovery settings retain active cache");
    write("config", "manager.properties", managerBefore);
    s.reset();
    check(s.listPackages().contains("NBKCasePath"), "reload restores configured defaults");
    check(s.discoveryJson().contains("config-2.3.3-20261102"), "status exposes running build ID");
    check(s.discoveryJson().contains(root.toString()), "status exposes actual packages root");
    check(s.fileDiagnostics("NBKCasePath").contains("NBKCasePath/Config"), "file diagnostics exposes actual search directory");
    pkg("NBKManualSecret");
    write("NBKManualSecret", "config.cnf", "timeout=123\npassword=[[encrypted:manual_handle]]\n");
    s.reset();
    check(get(s, "NBKManualSecret", "timeout", false).equals("123"), "unresolved secret does not block ordinary values");
    check(get(s, "NBKManualSecret", "password", false).equals("[[encrypted:manual_handle]]"), "unresolved handle marker preserved");
    rejects(() -> get(s, "NBKManualSecret", "password", true), "missing secret plaintext lookup fails closed");
    check(s.secretMetadata("NBKManualSecret").contains("\"configured\":false"), "missing handle listed for manual entry");
    String manualText = s.read("NBKManualSecret", "config.cnf");
    s.saveSecret("NBKManualSecret", "config.cnf", "password", "manual_handle", "manually-entered-test", ConfigStore.hash(manualText));
    s.reset();
    check(get(s, "NBKManualSecret", "password", true).equals("manually-entered-test"), "manual secret entry resolves after reload");
    check(s.secretMetadata("NBKManualSecret").contains("\"configured\":true"), "manual handle marked configured");
    pkg("NBKMalformed");
    write("NBKMalformed", "config.cnf", "broken=prefix[[encrypted:bad]]\nnormal=ok\n");
    s.reset();
    check(get(s, "NBKMalformed", "normal", false).equals("ok"), "malformed legacy reference does not block unrelated values");
    rejects(() -> get(s, "NBKMalformed", "broken", true), "malformed value retrieval fails closed");
    check(s.secretMetadata("NBKMalformed").contains("config.cnf: broken"), "malformed reference inventory includes file and key only");
    String configUnchanged = s.read("NBKManualSecret", "config.cnf");
    String storeVersion = ConfigStore.hash(s.read("NBKManualSecret", "config.secrets.cnf"));
    s.saveHandle("NBKManualSecret", "manual_handle", "updated-manually", storeVersion);
    check(s.read("NBKManualSecret", "config.cnf").equals(configUnchanged), "direct encrypted update preserves configuration file");
    rejects(() -> s.saveHandle("NBKManualSecret", "manual_handle", "stale", storeVersion), "direct encrypted update rejects stale version");
    s.reset();
    check(get(s, "NBKManualSecret", "password", true).equals("updated-manually"), "direct encrypted update activates after reload despite unrelated malformed value");
    String inventory = s.allSecretMetadata();
    check(inventory.contains("NBKManualSecret") && inventory.contains("manual_handle"), "single snapshot inventory includes all-package handles");
    check(inventory.contains("\"malformed\":true"), "snapshot inventory includes malformed diagnostics");
    check(s.allSecretMetadata() == inventory, "snapshot inventory JSON reused across requests");
    Path movedRoot = root.resolveSibling(root.getFileName() + "-offline");
    Files.move(root, movedRoot);
    try { check(s.allSecretMetadata().equals(inventory), "inventory remains available with all package files offline"); }
    finally { Files.move(movedRoot, root); }
    check(s.secretEditInfo("NBKManualSecret").contains(ConfigStore.hash(s.read("NBKManualSecret", "config.secrets.cnf"))), "edit obtains current disk version on demand");
    s.reset();
    check(s.allSecretMetadata() != inventory, "reload builds a fresh snapshot inventory");
    if (System.getenv("CONFIG_MASTER_KEY") == null) {
      Path localKey = Files.createTempFile("config-key-only-test-", ".key");
      Files.writeString(localKey, Base64.getEncoder().encodeToString(new byte[32]));
      write("config", "manager.properties", "environment=dev\nmasterKeyFile=" + localKey.toString() + "\n");
      ConfigStore noWrapper = new ConfigStore(root);
      noWrapper.reset();
      check(noWrapper.encrypt("NBKManualSecret", "test", "test-only").startsWith("ENCv1:"), "manager key path works without wrapper property or environment variable");
      Files.delete(localKey);
    }
    System.out.println("TOTAL " + count + " checks passed");
  }
}
