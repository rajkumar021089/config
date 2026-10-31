# Combined reset tests

Simulated IS APIs; actual server integration still requires validation.

```text
PASS startup loads config.cnf
PASS environment override wins
PASS UTF-8 preserved
PASS configured global fallback
PASS ignoreGlobalValues suppresses fallback
PASS legacy configFile basename maps to new file
PASS Save leaves active cache unchanged
PASS stale save rejected
PASS reset activates saved value
PASS restart loads saved files
PASS package traversal rejected
PASS file traversal rejected
PASS unrelated file access rejected
PASS symbolic link rejected
PASS config file keeps encrypted handle syntax
PASS secret file contains no plaintext
PASS false returns handle reference
PASS true returns plaintext
PASS plaintext not cached by default
PASS secret store rejects plaintext
PASS bad config rejects reset
PASS failed reset retains old snapshot
PASS migration defaults to inspectable dry run
PASS dry run never decrypts old secrets
PASS dry run writes no targets
PASS other stages explicitly reported
PASS apply requires matching plan
PASS legacy resolution failure aborts migration
PASS failed resolution writes no targets
PASS migration applies
PASS migrated secret resolves without original service
PASS migrated environment auto-loaded
PASS legacy file untouched
PASS migration report contains no secret values
PASS repeat apply refuses overwrite
PASS repeat apply preserves target
PASS wrapper input/output contract
PASS service plaintext flag
PASS missing suppression returns null
PASS missing default throws
PASS excluded interpolation flag rejected
PASS reset without token denied
PASS tampered token denied
PASS anonymous runtime denied
PASS anonymous admin denied
PASS bulk response cannot mutate internal snapshot
PASS bulk returns snapshot version
PASS concurrent snapshot readers remain consistent during reset
PASS explicit decrypted-cache option works
PASS decrypted-cache lookup returns value
PASS combined action reports configuration reloaded
PASS combined action invokes built-in reset once
PASS combined action publishes new configuration
PASS configuration validation failure skips built-in reset
PASS validation failure retains previous active configuration
PASS built-in reset failure is reported as partial success
PASS partial success preserves successful configuration reload
PASS configuration-only reset remains separate
TOTAL 58 checks passed
```

Latest loader run: 62 checks passed, including four NBK discovery/existing-file/cache/reload checks.

Case-sensitive Linux path fix: 66 checks passed, including exact Config/wxConfig.cnf discovery, JVM load, configFile lookup, save and reload.

Configurable discovery: 74 simulated checks passed, including arbitrary prefix/folder/filename, environment override, lookup, UI metadata, invalid path rejection and previous-cache retention.
