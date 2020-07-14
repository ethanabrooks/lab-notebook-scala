# Build
From root:
```bash
make deb
cd target
sudo apt install --reinstall ./runs_0.1_all.deb
```
Note: this only works on machines that support Debian.
# Run
```bash
runs --help
```
