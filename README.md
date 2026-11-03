# config — webMethods Integration Server configuration package

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
