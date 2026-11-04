# config — webMethods Integration Server configuration package

## Package values service add-on: 1.0

Run `config.services:getValuesForPackage` with `packageName=NBKCustomer` to return
all effective package configuration values. The output includes detailed
`contents[]` rows with source-file information, plus simple `keyValues[]` pairs
for Flow mapping. Global values are opt-in; encrypted values return references
unless `showEncryptedValues=true`.

- [Download the package values add-on](config-getValuesForPackage-addon.zip)
- [Installation and compilation instructions](config-getValuesForPackage-addon/README.md)
- [Input/output contract](config-getValuesForPackage-addon/SERVICE_CONTRACT.md)
- [Java service source](config-getValuesForPackage-addon/config/code/source/config/services.java)
- [Validation results](config-getValuesForPackage-addon/TEST_RESULTS.md)

Apply to an existing config 2.3.3 installation. The included merge helper backs
up the existing Java source and adds the new method and service metadata while
preserving local Java changes, settings and UI files. Compile against your
installed IS libraries and reload the package afterward.

Validation: 165 behavior and installer checks passed, plus Designer signature
consistency checks, using simulated IS APIs. The target Integration Server is
unverified; see the validation notes for compiler and deployment limits.

## Latest navigation patch: 2.3.3 / 233-nav2

This revision addresses the Packages page remaining on “Connecting to Integration Server” before sending its status request. It adds versioned script loading, guarded page initialization, visible startup diagnostics, full document navigation, and status refresh when a page is restored.

- [Download the navigation patch](config-2.3.3-menu-navigation-patch.zip)
- [Installation and rollback instructions](config-2.3.3-menu-navigation-patch/INSTALL.txt)
- [Patch source files](config-2.3.3-menu-navigation-patch/config/pub)
- [Validation results and limitations](config-2.3.3-menu-navigation-patch/VALIDATION.txt)

Apply the patch to an existing config 2.3.3 installation. Back up `packages/config/pub`, then copy **all** files from the patch’s `config/pub` folder into it, including `console-bootstrap.js`. Hard-refresh `/config/packages.dsp` and test Packages → Files → Packages.

Expected request: `POST /invoke/config.services/dispatch` with form field `action=status`.

The root `console.js` is a copy of this patch’s main script; install the complete patch to obtain the matching bootstrap and DSP pages. No Java recompilation is required for these pub assets.

Validation: 15 local JavaScript integration checks passed using parsed DSP element fixtures and simulated service responses. Browser rendering and the target Integration Server remain unverified. See the validation file for details.

