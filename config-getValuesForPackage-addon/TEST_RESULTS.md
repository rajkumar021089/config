# Validation results

Validated on 4 November 2026 using OpenJDK 21.0.13.

| Check group | Result |
| --- | --- |
| Existing config behavior regression suite | 98 passed |
| New package-value behavior checks | 57 passed |
| Installer preservation / conflict / idempotency checks | 10 passed |
| Designer signature dimensions and Java fragment/source consistency | Passed |

Total: **165 behavior and installer checks passed**, plus namespace consistency checks.

The new service was compiled together with the existing class and simulated IS
API fixtures. The runtime lacks the JDK ct.sym archive, so compilation used
`-source 11 -target 11` on JDK 21, not `--release 11`. This verifies Java 11
language/bytecode compilation and execution on JDK 21; it does not substitute
for compiling against the real IS libraries and supported server JDK.

Covered behavior includes all active keys without a keys input; 1,501-key
results; exact output types; environment precedence; inactive source-file
information; global opt-in and collisions; secret references and explicit
plaintext resolution; missing/malformed secrets; authentication; key prefix
filters; Unicode/empty values; returned-object isolation; saved versus active
values; concurrent reload consistency; and values, source information and
plaintext resolution remaining available while the package directory is moved
offline after startup. No legacy service calls occurred.

The installer was checked for preserving local Java changes, settings and UI
files, backing up original source, doing no writes in check mode, idempotency,
and rejecting conflicting metadata or a renamed package before changing files.

**Live Integration Server deployment was not available.** Compile the merged
service against your installed IS libraries, reload the package, inspect the
signature in Designer, and run it on a development IS before deployment.

Reproduce locally from this extracted folder:

```bash
python3 tests/run_tests.py
python3 tests/run_installer_tests.py
```

Full outputs: tests/BEHAVIOR_RESULTS.txt and tests/INSTALLER_RESULTS.txt.
