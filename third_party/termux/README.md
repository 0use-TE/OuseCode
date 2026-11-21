# Termux terminal components

Source: https://github.com/termux/termux-app/tree/v0.118.3

Source archive SHA256: `debd53a911c30f578c5b3600e5609a22b576491b20ce632e155457596ee1d291`.

Only the `terminal-emulator/src/main` and `terminal-view/src/main` sources are vendored.
TerminalSession.java adds an external PTY backend for the authorized Shizuku process,
including remote resize, wait, close and connection failure cleanup. Other Java, C
and resource files are unmodified. The two source manifests have their legacy
`package` attributes removed; Gradle namespaces supply these for AGP 9. Separate build
scripts target this application's ARM64 ABI and installed NDK. No Termux application
or Termux Linux environment is bundled by these modules.

See LICENSE.md for upstream GPLv3 and Apache-2.0 attribution. Full license texts ship
in the application's assets/licenses directory.
