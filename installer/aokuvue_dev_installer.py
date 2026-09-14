"""Dev-channel entry point for the standalone Aokuvue installer.

This intentionally installs beside the stable application and follows only the repository's
``dev`` branch.  The shared installer implementation remains the single source of packaging,
JDK verification and atomic update behavior.
"""

from __future__ import annotations

import aokuvue_installer as installer


installer.APP_NAME = "Aokuvue Dev"
installer.INSTALLER_VERSION = "0.1.3"
installer.SOURCE_BRANCH = "dev"
installer.USER_AGENT = f"AokuvueDevInstaller/{installer.INSTALLER_VERSION}"


if __name__ == "__main__":
    raise SystemExit(installer.main())
