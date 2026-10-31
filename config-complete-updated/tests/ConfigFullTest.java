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
        "environment=dev\nglobalPackage=config\ncacheDecryptedSecrets=false\n");
    write("config", "config.cnf", "globalKey=global\n");
    write("NBKDemo", "config.cnf", "timeout=30000\nmessage=common\ntamil=வணக்கம்\na=one\nb=one\n");
    write("NBKDemo", "config-dev.cnf", "message=dev\n");
    pkg("OtherPackage");
    write("OtherPackage", "config.cnf", "bad=\\uZZZZ");
    pkg("NBKExisting");
    write("NBKExisting", "config.cnf", "existing.timeout=70000\n");
    pkg("NBKCasePath");
    Files.move(root.resolve("NBKCasePath/config"), root.resolve("NBKCasePath/Config"));
    Files.writeString(root.resolve("NBKCasePath/Config/wxConfig.cnf"), "case.timeout=70000\n");
    ConfigStore s = new ConfigStore(root);
    s.reset();
    check(s.listFiles("NBKCasePath").contains("wxConfig.cnf"), "discovers exact Config/wxConfig.cnf path");
    check(get(s, "NBKCasePath", "case.timeout", false).equals("70000"), "legacy mixed-case file loads JVM snapshot");
    check(s.lookup(s.snapshot(), "NBKCasePath", "case.timeout", "wxConfig.cnf", false, false, false).equals("70000"), "mixed-case configFile lookup resolves");
    String caseText = s.read("NBKCasePath", "wxConfig.cnf");
    s.save("NBKCasePath", "wxConfig.cnf", "case.timeout=80000\n", ConfigStore.hash(caseText));
    s.reset();
    check(get(s, "NBKCasePath", "case.timeout", false).equals("80000"), "save and reload preserve exact legacy file path");
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
    check(
        s.lookup(s.snapshot(), "NBKDemo", "message", "wxconfig.cnf", false, false, false)
            .equals("common"),
        "legacy configFile basename maps to new file");
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
    write(
        "NBKLegacy",
        "wxconfig.cnf",
        "url=https://example.test\npassword=[[encrypted:legacy_handle]]\n");
    write("NBKLegacy", "wxconfig-dev.cnf", "timeout=9000\n");
    write("NBKLegacy", "wxconfig-prod.cnf", "timeout=1000\n");
    Service.legacy.put("NBKLegacy/password", "migrated-secret-only");
    Service.legacyCalls = 0;
    services.MigrationResult plan =
        s.migrate("legacy.config:getValue", "NBKLegacy", true, null, true);
    check(plan.status.equals("DRY_RUN"), "migration defaults to inspectable dry run");
    check(Service.legacyCalls == 0, "dry run never decrypts old secrets");
    check(!Files.exists(root.resolve("NBKLegacy/config/config.cnf")), "dry run writes no targets");
    check(
        plan.json.contains("skippedOtherStages") && plan.json.contains("wxconfig-prod.cnf"),
        "other stages explicitly reported");
    rejects(
        () -> s.migrate("legacy.config:getValue", "NBKLegacy", false, "wrong", true),
        "apply requires matching plan");
    Service.failLegacy = true;
    rejects(
        () -> s.migrate("legacy.config:getValue", "NBKLegacy", false, plan.planId, true),
        "legacy resolution failure aborts migration");
    check(
        !Files.exists(root.resolve("NBKLegacy/config/config.cnf")),
        "failed resolution writes no targets");
    Service.failLegacy = false;
    services.MigrationResult applied =
        s.migrate("legacy.config:getValue", "NBKLegacy", false, plan.planId, true);
    check(applied.status.equals("APPLIED"), "migration applies");
    check(
        get(s, "NBKLegacy", "password", true).equals("migrated-secret-only"),
        "migrated secret resolves without original service");
    check(get(s, "NBKLegacy", "timeout", false).equals("9000"), "migrated environment auto-loaded");
    check(
        Files.readString(root.resolve("NBKLegacy/config/wxconfig.cnf")).contains("legacy_handle"),
        "legacy file untouched");
    check(
        !applied.json.contains("migrated-secret-only"),
        "migration report contains no secret values");
    String migrated = s.read("NBKLegacy", "config.cnf");
    rejects(
        () -> s.migrate("legacy.config:getValue", "NBKLegacy", false, plan.planId, true),
        "repeat apply refuses overwrite");
    check(s.read("NBKLegacy", "config.cnf").equals(migrated), "repeat apply preserves target");
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
    check(out(secret, "propertyValue").equals("migrated-secret-only"), "service plaintext flag");
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
        get(s, "NBKLegacy", "password", true).equals("migrated-secret-only"),
        "decrypted-cache lookup returns value");

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
    System.out.println("TOTAL " + count + " checks passed");
  }
}
