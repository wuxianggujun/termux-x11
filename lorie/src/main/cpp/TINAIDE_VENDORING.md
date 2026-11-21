# TinaIDE X.Org source directories

The following directories are intentionally stored as ordinary source files in the
`termux-x11` repository rather than as nested Git submodules:

- `libepoxy`
- `libx11`
- `libxkbfile`
- `libxtrans`
- `pixman`
- `xkbcomp`
- `xserver`

These sources carry TinaIDE's Android/Lorie integration changes and match the
patches under `patches/`. Keeping them in the parent repository makes the exact
source used by the Lorie build reviewable and editable without maintaining seven
additional remotes and gitlinks. The other X.Org dependencies remain submodules.

Edit the source files directly and commit them with the `termux-x11` changes.
The CMake patch helper is idempotent: it checks whether a patch is already
applied before attempting to apply it, so a clean checkout of these patched
source directories does not apply the same changes twice. When updating a source
snapshot, review its matching patch and refresh or remove that patch if the
snapshot no longer contains its changes. Keep upstream license and notice files
with each vendored source directory.