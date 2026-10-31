# Configurable discovery update

Package prefix, business configuration directory names, and common filenames now come from manager.properties. See DISCOVERY_SETTINGS.txt for the exact properties and manual-edit instructions. No Java changes are needed for later path changes. Save still writes disk only; Reload Configuration publishes values to JVM memory. 74 simulated checks passed; actual IS verification remains required.

# Existing Linux path compatibility fix

Supports NBK*/Config/wxConfig.cnf exactly, as well as config/config.cnf. Existing legacy files are displayed, read, edited and loaded without renaming. All NBK packages shows both common filenames. New config.cnf takes precedence if both common files exist in the same directory. Both config and Config directories in the same package cause a clear validation error rather than choosing silently. This does not decrypt original WxConfig secrets: legacy encrypted references still require migration.

Install this complete package preserving your existing config/config data and external key. Compile all config.services Java services in Designer, reload the config package, Ctrl+F5 the DSP, then reload configuration on each node.

66 simulated checks passed, including discovery, lookup, save and reload of exact Config/wxConfig.cnf path. Actual IS validation required.

# Complete updated config package

This archive contains the full source-only package, not only a patch.

Included updates: NBK-only business-package discovery; existing config/config.cnf loaded at startup/reload; active-environment overrides; in-memory getValue; all-NBK DSP file listing; Save closes the editor and scrolls to success; combined configuration reload and IS service-result reset. Explicit globalPackage fallback remains supported.

UPGRADE AN EXISTING INSTALLATION
Back up the current config package first. Copy the config folder into the IS packages directory, merging code/ns/pub assets. PRESERVE YOUR EXISTING config/config DIRECTORY: do not overwrite manager.properties, config.cnf, environment files, or config.secrets.cnf with the bundled examples. Keep the external master key unchanged. Compile all Java services under config.services in Designer (Ctrl+S); logic is visible in Source and Shared Source. Reload the config package, refresh DSP with Ctrl+F5, then use Reload Configuration on each node. Validate a known existing NBK key through getValue.

FRESH INSTALLATION
Use the bundled config folder, configure manager.properties for your environment, compile Java services, then reload the package. Existing NBK package files are discovered without migration. Encryption requires an external master key; original WxConfig secret handles require migration to this package's secret store. See the full guide below.

VALIDATION
62 simulated IS checks passed. Java compiled for release 11; JavaScript syntax and file-listing/save handlers checked. Actual IS integration and load testing remain required. No compiled classes or custom JAR are included.

# config 2.0 — full source-only Integration Server package

This delivery replaces the earlier prototype. It contains the configuration manager,
9 Designer-visible Java services, a related DSP console, encryption, guarded migration,
and a command-line migration client. No custom JAR or compiled classes are shipped.

**Validation:** 50 checks pass on Java 17 against simulated IS APIs, using the class
reconstructed from the actual Java service fragments and Shared Source. This is NOT
an actual IS 10.15/12.1 compatibility or capacity certification. Compilation, ACLs,
DSP HTTP responses, original WxConfig behavior and peak load must be checked on your
development IS. The field names match the supplied signature; unobserved legacy
flag defaults/semantics are defined below, not claimed to be reverse-engineered.

## 1. Install and compile

1. Back up any earlier `config` package. Replace its directory with this delivery's
   `config` directory; do not overlay an old JAR/classes/namespace. Preserve your
   prior configuration files separately. No files on your machine are changed by
   extracting this ZIP unless you explicitly copy them.
2. Copy `config` to `<IS>/instances/<instance>/packages/config` (or the actual
   packages directory of your runtime). The ZIP is a directory-copy delivery,
   not an IS replication/inbound archive.
3. Set `config/config/manager.properties` to your actual environment, for example
   environment=TEST. The suffix is case-sensitive; config-TEST.cnf differs from
   config-test.cnf. Global fallback is disabled unless globalPackage is supplied.
4. From the extracted delivery root compile with the IS-supported JDK:
   ```cmd
   java tools/CompilePackage.java "C:\SoftwareAG" "C:\SoftwareAG\IntegrationServer\instances\default\packages\config"
   ```
   Use your actual suite root (including common/lib) and package path. If Java is
   not on PATH, use its full java.exe path. JDK 11+ is required. The helper writes
   ordinary class files under config/code/classes, not a custom JAR.
5. Load/reload package `config` in IS Administrator > Packages > Management.
   Manifest startup is config.services:startup. If package-root discovery fails,
   supply JVM property `-Dconfig.packagesRoot=<absolute packages directory>`.
6. In Designer set List/Read/Write/Execute ACLs on folder `config.services` to
   Administrators, inherited by all services. Turn OFF pipeline/document audit
   logging for getValue/getValues/migrate/dispatch and secret operations. Do not
   enable request-body or pipeline logging that captures plaintext secrets.
   Java checks ACLs as well; it fails closed. ACL metadata is not automatically
   written into IS server-wide ACL maps.
7. Run config.services:startup and visit:
   `https://<IS-host>:<admin-port>/config/index.dsp`
   The Solutions > Configuration Management link registers through internal
   wm.server.ui menu services; this registration needs verification on your release.
   The direct DSP URL is the fallback if the menu service differs.

All implementation is visible in Designer:
- Individual service > Source: actual service body.
- config.services > Shared > Source: file, snapshot, crypto and migration helpers.
- config.services > Shared > Imports: standard Java and IS imports.
- Plain copies: designer-source/*.java.txt and IMPORTS.txt.
- Complete Java source: config/code/source/config/services.java.
If imported source does not display in a particular Designer version, manually
create the services using their node.ndf signatures, paste the supplied bodies,
Shared Source and Imports, compile/save, then reload the package. Do not paste a
whole Java class into an individual service body.

## 2. Change only your wrapper INVOKE

New service: **config.services:getValue**.
Output: **propertyValue** (String).

| Input (String) | Defined behavior in this delivery |
|---|---|
| key | Required, case-sensitive property name |
| wxConfigPkgName | Logical package owning the configuration; preserve existing names |
| noServiceException | Blank/false: throw for missing key. true: propertyValue=null for missing key only |
| substituteVariables | Blank/false accepted. true rejected because interpolation is excluded |
| ignoreGlobalValues | Blank/false: fall back to configured globalPackage. true disables fallback |
| scanAllConfigurations | Blank/false: package/global lookup only. true: if unresolved, search cached effective packages; ambiguous matches throw |
| noVariableInterpolation | Blank/true/false accepted; values remain literal, interpolation is not implemented |
| configFile | Blank: effective common + current environment. Otherwise a supported basename such as config.cnf; wxconfig.cnf maps to config.cnf |
| showEncryptedValues | true: return plaintext for an encrypted reference. Blank/false: return [[encrypted:handle]] |

These defaults, masking and scan semantics need side-by-side verification against
YOUR original service. Only showEncryptedValues=true => plaintext was confirmed
by the user. Do not claim exact unobserved legacy behavior from screenshots alone.
configFile does not accept arbitrary paths. Embedded secret references inside a
larger string and expression interpolation are explicitly unsupported.

For an application wrapper identity, create a dedicated reader ACL and configure
`-Dconfig.runtimeAcl=YourConfigReaderACL`. Set only getValue/getValues Execute ACLs
accordingly. Keep all admin/migration/lifecycle services Administrators-only.
Readers can read all packages under this package-wide ACL; there is no per-package
reader permission in v2. Do not use Everyone for secret access.

`config.services:getValues` optionally retrieves multiple keys with one invocation:
- inputs wxConfigPkgName, keys (String List), ignoreGlobalValues, showEncryptedValues
- outputs values (Document) and configurationVersion (String)
It captures one immutable snapshot so a reset cannot mix versions in that response.
Caller changes to the returned document cannot modify the cached configuration.

## 3. Configuration and secret storage

Per logical package:
```
<Package>/config/config.cnf
<Package>/config/config-<environment>.cnf
<Package>/config/config.secrets.cnf
```
Common keys load first; active-environment keys override common. Files use UTF-8
Java properties syntax. Properties.store escapes ':' as '\:' on disk; reading it
returns the original marker. Example logical values:
```
customer.timeout=30000
TOKENIZATION_SecretKey=[[encrypted:TOKENIZATION_secretkey]]
```
config.secrets.cnf maps TOKENIZATION_secretkey to NEW AES-256-GCM ciphertext.
The reference is preserved; the old encryption format/key is not reused.
The new key must be provisioned before migrating/encrypting secrets:
```
java tools/GenerateMasterKey.java
```
Store the generated Base64 line in an OS-protected file outside ALL package folders,
readable by the IS service identity. Never put it into chat, Git, the package, or
ordinary configuration files. Provision its JVM property before starting IS:
`-Dconfig.masterKeyFile=C:/IS-secrets/config-master.key`
Linux: `-Dconfig.masterKeyFile=/etc/is-secrets/config-master.key`
Alternatively set process environment CONFIG_MASTER_KEY. It takes precedence.
The key contains 32 random bytes. Back it up separately from package files.
Use the same protected key on nodes receiving the same migrated ciphertext.

Key material is loaded once and retained in JVM memory. Runtime secret retrieval
never reads the key file. Rotation is not automated: plan re-encryption and restart;
do not simply replace the key file. Ciphertext uses a fresh 12-byte nonce, 128-bit
GCM tag and package+handle authentication. Copying a secret to a different logical
package/handle requires re-encryption. Empty secrets are allowed; null is not.

By default only ciphertext is cached; showEncryptedValues=true decrypts on retrieval.
For a measured performance need, explicitly set cacheDecryptedSecrets=true in
manager.properties and reset. This retains plaintext secrets for the snapshot's
lifetime in heap memory. It is a security decision, not enabled by default. Plaintext
also necessarily exists temporarily in migration/caller pipelines and Java Strings;
those Strings cannot reliably be zeroized. Keep diagnostics and heap dumps protected.

## 4. DSP pages (related layout, original assets not copied)

- index.dsp / packages.dsp: package list and file links.
- files.dsp: file browser, create/view/edit, optimistic Save, encrypted-value entry.
- encrypted.dsp: package-scoped active encrypted handle inventory; never auto-reveals.
- migration.dsp: dry run / apply with report.
- settings.dsp: loaded version, timestamp and Reset Server Cache.

The pages are genuine package-hosted .dsp files with an HTML/JavaScript frontend
calling IS services. No custom JSP container, third-party frontend dependency or
external CDN is required. CSS/navigation resemble the provided reference; this
is not a pixel-exact or behavioral clone of proprietary WxConfig pages.

**Save** writes disk only. **Reset Server Cache** validates all files/secrets and
publishes one new snapshot. Failed validation keeps the previous snapshot. Startup
loads the saved snapshot. Viewing/re-scanning files does not reload runtime values.
Encrypt-and-save rewrites property formatting/comments. Save editor changes first.
Files must support atomic replacement; old service source files are never edited.

## 5. One migration command (recommended: dry run first)

Run this on the OLD environment with WxConfig still available AND the new config
package installed on that IS. This command needs an interactive terminal and a
JDK 11+ client. HTTPS certificate validation remains enabled. Use a trusted admin
endpoint; install your enterprise CA in the client JDK truststore if necessary.

Windows CMD from the extracted delivery root:
```cmd
tools\migrate.cmd https://is-host:6655 Administrator actual.wxconfig.namespace:getValue NBK
```
Linux/macOS or direct Java:
```
java tools/MigrateConfig.java https://is-host:6655 Administrator actual.wxconfig.namespace:getValue NBK
```
Replace the original namespace with its exact fully qualified name. It is not
visible in the supplied screenshots, so this delivery deliberately does not guess.
Password is requested interactively, never passed in the command line. The client
requests an admin token and performs a server-side dry run. Add --apply to perform
the reviewed operation; the client always performs a fresh dry run first:
```cmd
tools\migrate.cmd https://is-host:6655 Administrator actual.wxconfig.namespace:getValue NBK --apply
```

Equivalent direct Designer invocation:
1. Run config.services:getAdminToken; copy output token.
2. Run config.services:migrate with token, sourceService, packagePrefix=NBK,
   dryRun=true. Review result and planId.
3. Run migrate with dryRun=false, the same planId, activate=true and valid token.

Dry run discovers common wxconfig.cnf and wxconfig-<current environment>.cnf for
packages matching the prefix. It reports destination paths, key count, referenced
secret count, destination conflicts and other-stage files skipped. It NEVER calls
the legacy service for plaintext secrets and writes nothing. Apply rechecks the
plan, refuses existing destination files, resolves every referenced secret, and
only then writes new files. Originals remain untouched. Repeating apply refuses
to overwrite existing output. If atomic batch writing fails, moved targets are
rolled back; an incomplete rollback is reported. Take normal filesystem backups
before migration and do not edit files concurrently with it.

For each encrypted property the migration invokes the original service with:
key, wxConfigPkgName, configFile=<source basename>, showEncryptedValues=true,
noVariableInterpolation=true, substituteVariables=false, ignoreGlobalValues=true,
noServiceException=false, scanAllConfigurations=false. It reads propertyValue,
re-encrypts it internally and clears the request/response value fields. No plaintext
export file or secret values in migration reports. On success activate=true loads
the newly migrated files immediately. It affects this IS JVM only.

### Migration scope / checks that matter

- Source filenames: wxconfig.cnf and wxconfig-<environment>.cnf. Arbitrary file
  names, host/host+port stages, custom include chains and database-backed settings
  are not inferred. Other stage files are reported, not mixed into this environment.
- Run separately for DEV/UAT/PROD with the correct environment setting. For four
  nodes of ONE environment, migrate once and distribute the SAME reviewed generated
  files and protected key to all four; then reset and compare configurationVersion.
- Secret-only entries in WxConfig's registry with NO reference in the scanned
  files cannot be enumerated through getValue alone. Add explicit references to
  a reviewed staging copy/source file or provide a separate registry inventory
  before claiming those entries migrated. The report counts referenced secrets only.
- Legacy configFile handling and cryptographic access depend on your WxConfig
  version and ACLs. A failed/null/unresolved legacy response aborts before writes.
- Interpolation syntax ${...} or embedded/malformed encrypted markers aborts
  migration because interpolation was excluded. Do not silently flatten unknown
  settings. General key/value syntax and common/environment precedence are supported.
- If a handle resolves differently in two source files within the same package,
  migration rejects the conflict instead of choosing a secret silently.
- The original WxConfig is used ONLY during migration. Runtime/startup/reset use
  only new local files; the wrapper can be switched after validation.

## 6. Services

| Service | Role |
|---|---|
| config.services:getValue | Wrapper-compatible field names; propertyValue output |
| config.services:getValues | Bulk keys, one snapshot/version |
| config.services:startup | Initial cache load and Solutions menu registration |
| config.services:shutdown | Remove menu entry (administrator context) |
| config.services:menu | Menu callback |
| config.services:getAdminToken | Short-lived username/JVM-bound mutation token |
| config.services:resetServerCache | Explicit reset, requires admin token |
| config.services:migrate | Guarded legacy migration, dryRun defaults true |
| config.services:dispatch | DSP backend; mutations require admin token |

CSRF tokens expire in 30 minutes and on package reload. Refresh status/reload page
for a new token. Use a direct node admin URL, not a load-balanced URL; a token issued
on one node is invalid on another. No network or shared/distributed cache is used
in the runtime path. Global fallback is a configured logical package, not a guessed
copy of WxConfig internal global-resolution rules.

## 7. Development smoke test and cutover

1. Compile and load; verify Source and Shared Source display.
2. Apply Administrators ACLs; verify anonymous and non-admin admin calls denied.
3. getValue(wxConfigPkgName=config,key=demo.timeout) returns propertyValue=30000.
4. Edit/save demo.timeout=60000; getValue remains 30000.
5. Reset via DSP; getValue becomes 60000. Restart dev IS; still 60000.
6. Provision master key; save a dummy secret using the DSP. Reset. True returns
   plaintext; false returns the marker. No passwords appear in reports/logs.
7. Dry-run migration on a small package prefix. Validate counts, skipped stages,
   conflicts, legacy configFile behavior and sourceService ACLs. Apply in development.
8. Compare original and replacement outputs AND failures for ordinary keys,
   secret keys, empty/missing values, package/global collisions and flags your
   wrapper actually uses. Do not capture plaintext secrets into comparison reports;
   compare internally and record only match/mismatch counts.
9. Change only the wrapper INVOKE to config.services:getValue after equivalence
   checks. Keep a rollback version of the wrapper and old WxConfig untouched.
10. Deploy same reviewed files to the other nodes, reset and compare versions.

## 8. Your throughput target: four nodes, provisionally 8,000 lookups/s/node

The core captures an immutable local snapshot; ordinary reads use maps without a
global read lock, disk access, parsing or network calls. ACL checks remain on every
service call; reflection method metadata is cached, authorization decisions are not.
No per-lookup logging/counters are added. Bulk calls reduce repeated service overhead.
Secrets default to decrypt-on-read; benchmark their proportion separately. Optional
plaintext snapshot caching trades heap exposure for reduced crypto work.

This design is NOT proof of 8,000/s capacity. On actual IS, test each node at 8,000/s
and 12,000/s, with realistic business traffic, normal ACLs, secret proportion and
concurrent reset. Measure p50/p95/p99 latency, CPU, allocations, GC and error rate.
The old 27 million/day and 50 lookups/transaction figures imply 15,625/s aggregate
average; 8,000/s/node is a provisional peak target, not a confirmed measurement.
Do not run a synthetic load generator on production without a planned test window.

## 9. Test source

Local tests use minimal simulated IS signatures, never proprietary IS libraries.
They test migration, encryption, contract fields, cache publication and concurrent
snapshot readers. Never install the tests/fixtures classes on IS.
Compile/run on a development workstation (JDK; test-only key):
```
javac --release 11 -encoding UTF-8 -d test-classes <fixture-java-files> config/code/source/config/services.java tests/ConfigFullTest.java
java -cp test-classes ConfigFullTest
```
Set CONFIG_MASTER_KEY to a test-only Base64 32-byte key in that test process. The
all-zero key in test instructions is never a deployment key. TEST_RESULTS.md records
actual checks and explicitly separates local simulation from real IS validation.

No original WxConfig source, licensed implementation, original encryption keys or
proprietary DSP assets are bundled. Excluded: adapter/WS/trigger auto-setup,
interpolators, auditing/publishing/deployment automation and orphaned-key analysis.
