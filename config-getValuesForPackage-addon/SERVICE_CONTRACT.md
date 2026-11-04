# config.services:getValuesForPackage

## Input

| Field | Designer type | Default / meaning |
| --- | --- | --- |
| packageName | String | Required package name; alternatively use showPkg |
| showPkg | String | Screenshot-compatible input name; optional alias of packageName |
| ignoreKeysStartingWith | String List | Optional case-sensitive prefixes; null/empty entries ignored |
| includeGlobalValues | String | `false`; `true` also includes keys from the configured globalPackage |
| showEncryptedValues | String | `false`; `true` resolves effective encrypted references to plaintext |

If both name fields are supplied, they must agree. Surrounding package-name
whitespace is trimmed. Boolean fields accept true/false without case sensitivity.
There is no `keys` input and no 1,000-key cap imposed by the existing selected-key
API. Only packages already loaded by the configured prefix or globalPackage are
available; this service does not discover another package on demand.

## Output

| Field | Designer type | Meaning |
| --- | --- | --- |
| packageName | String | Requested package |
| contents | Document List | One row per effective key, sorted by key |
| keyValues | Document List | Same keys with scalar effective values, for easy Flow mapping |
| totalCount | String | Number of returned contents rows after filtering |
| environment | String | Environment of the captured snapshot |
| configurationVersion | String | Version of the captured snapshot |

### contents[]

| Child field | Designer type | Meaning |
| --- | --- | --- |
| package | String | Package owning this value; can be globalPackage when globals are requested |
| key | String | Configuration key |
| value | String | Effective value from common file plus active environment overrides |
| valueList | String List | One element containing value; the properties engine stores scalar values |
| variable | String | `true` if the raw effective property contains `${`; otherwise `false` |
| isGlobalValue | String | `true` if the owner is the configured globalPackage |
| containsUnresolvedInterpolator | String | `true` when a `${` token remains in the raw effective property |
| files | Document List | Cached source-file definitions of this key in the owning package |

The engine does not interpolate `${...}`, so variable expressions remain literal
and both interpolation-related flags are true for those expressions. These are
the add-on's explicit semantics, not a claim about the old WxConfig UI internals.
Commas and newlines are preserved as part of a scalar; valueList is not split.

### contents[]/files[]

| Child field | Designer type | Meaning |
| --- | --- | --- |
| fileName | String | Logical cached filename used by the existing store |
| value | String | Raw property value in that source; encrypted references stay references |
| isActive | String | `true` for common config.cnf or config-&lt;active environment&gt;.cnf |
| isEffective | String | `true` for the winning definition of this key |

File rows include inactive environment definitions of an effective key. A key
found only in an inactive environment file has no effective value and does not
appear in contents or keyValues. For file-editing inventory, use the existing
Files page rather than treating this service as a filesystem directory listing.

`fileName` is a **logical snapshot name**, not an absolute path or an assertion
about the current file on disk. Under the standard settings these names are
config.cnf and config-dev.cnf, etc. Custom common-file aliases are normalized by
the existing engine; individual definitions already collapsed by that engine
cannot be reconstructed by this add-on. Secret-store ciphertext and handles not
referenced by an effective property are not emitted as configuration keys.

### keyValues[]

| Child field | Designer type | Meaning |
| --- | --- | --- |
| key | String | Same key as corresponding contents row |
| value | String | Same scalar effective value as corresponding contents row |

## Lookup and errors

- Local package values override global collisions. Global keys are opt-in.
- Startup or the existing store's first invocation initializes the snapshot.
  Subsequent enumeration performs no filesystem reads or service invocations.
  All rows, source details, secrets and version use one captured snapshot.
- Save updates files. Existing configuration reload activates saved changes.
- Runtime authorization uses the same `config.runtimeAcl` setting as getValue,
  defaulting to Administrators, and checks the caller on every invocation.
- Default listings retain missing or malformed encrypted markers for inspection.
  Explicit plaintext requests fail if an effective encrypted value cannot be
  resolved. No partial output arrays are published by a failed invocation.
- Missing/invalid package names, conflicting aliases, unloaded packages and
  invalid flag values raise a ServiceException.
- Output documents and arrays are detached copies; editing them cannot change
  the cached configuration.

## Documentation reference

IBM documents Java services using an IData pipeline for both inputs and outputs,
and Designer's Input/Output tab for the declared service signature:
[Building Java Services, Integration Server 12.1](https://www.ibm.com/docs/en/webmethods-integration/wm-integration-server/12.1.0?topic=guide-building-java-services).
The service and its contract in this ZIP are custom project code.
