## Install

CloudStream → Settings → Extensions → Add repository:

```
https://raw.githubusercontent.com/dipender98/Indflix/builds/repo.json
```

## Project structure

This repository contains two separate CloudStream plugins: `Multimovies` and
`OTTMirror`. Each module is organized by responsibility under `plugin/`,
`core/`, and `sources/` (with `stream/` in OTTMirror). See
[ARCHITECTURE.md](ARCHITECTURE.md) before adding new files.

## License

MIT. See [LICENSE](LICENSE).

This plugin does not host content; it aggregates public embed hosts. Use at
your own risk and only with content you have rights to.
