# Get all configuration values for a package

**Service:** `config.services:getValuesForPackage`  
**Required input:** `packageName`, for example `NBKCustomer`  
**Add-on version:** 1.0, 4 November 2026  
**Base:** the existing config 2.3.3 implementation

Pass a package name to get every effective key and value in that package. No key
list is required. `contents[]` provides the detailed structure shown in the
reference screenshot. `keyValues[]` provides simple key/value pairs for your
existing wrapper. Source details come from the same loaded JVM snapshot.

This is an extension of your **config** implementation. The visible field names
from the screenshot are reproduced, with the exact new contract documented in
[SERVICE_CONTRACT.md](SERVICE_CONTRACT.md). The screenshot does not reveal the
legacy service's `files` children or all its field semantics; this add-on does
not claim binary or behavioral compatibility with that private UI service.

## Install into your existing config package

Extract this ZIP into a separate working folder. It is an add-on, not a complete
package archive for Integration Server's package installer.

1. From the extracted folder, run the merge helper against the installed package:

   ```bash
   python3 tools/install_addon.py /path/to/IntegrationServer/instances/default/packages/config
   ```

   Windows example:

   ```bat
   py tools\install_addon.py "C:\SoftwareAG\IntegrationServer\instances\default\packages\config"
   ```

   The helper backs up the original source, appends only the new Java method,
   and adds its `node.ndf` and `java.frag`. Existing methods, settings, encrypted
   stores, UI files, and menu-navigation changes are preserved. Use `--check`
   to inspect the planned files without writing. A different existing service
   with the same name stops the helper before any edits.

2. Compile the installed `config.services` class against your real IS libraries.
   You can use the included helper with the JDK supported by your IS installation:

   ```bash
   java tools/CompilePackage.java /path/to/SoftwareAG /path/to/IntegrationServer/instances/default/packages/config
   ```

   Alternatively, refresh the package in Designer, open the new Java service,
   and save/compile it. Use the same compiler level as the server.

3. Reload the config package and refresh Designer. If a new Java method is still
   unavailable after package reload, restart the development IS to clear its
   loaded Java class. Perform the compile/reload on each target IS node.

4. Run `config.services:getValuesForPackage` with `packageName=NBKCustomer`.
   Replace the example with a package loaded by your actual `packagePrefix`
   setting. An empty loaded package returns empty arrays; an unknown or unloaded
   package raises a ServiceException.

The full `config/code/source/config/services.java` in this ZIP is a reference
class based on your retrieved 2.3.3 release. Prefer the merge helper when you
have local Java changes. The add-on preserves the existing engine BUILD_ID;
the new service is verified by its presence and execution.

## Designer-only installation

If Python is unavailable, create the Java service `getValuesForPackage` in the
**same Java folder/class as your existing config services**. Paste
`designer-source/getValuesForPackage.java.txt` into its Source body. It reuses
the existing `ConfigStore`, `store`, `authorize`, `input`, `output`, `flag`, and
`failure` helpers already in that class. Add the signature listed in
SERVICE_CONTRACT.md, save/compile, and reload.

For a renamed package or namespace such as NBKConfig, use this Designer route
in the actual folder that owns those helpers. This method is not a standalone
replacement for the shared store. The automated installer intentionally checks
for the canonical config package instead of guessing how a renamed copy was set up.

## Mapping in your wrapper

| Wrapper field | New service field |
| --- | --- |
| Input `packageName` | Input `packageName` |
| Output `keyValues[]/key` | Output `keyValues[]/key` |
| Output `keyValues[]/value` | Output `keyValues[]/value` |
| Detailed package output | Output `contents[]` |

Globals are excluded unless `includeGlobalValues=true`. Encrypted values return
their stored reference unless `showEncryptedValues=true`. Plaintext requests
use the existing authorization and secret resolver. Save changes on disk first,
then use your configuration reload action to activate them in memory.

See TEST_RESULTS.md for the checks and deployment limits. The `tests/fixtures`
directory simulates IS APIs for local tests; it is not installed into IS.
