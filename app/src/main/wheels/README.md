# libloot Android wheel

`app/build.gradle` installs the `libloot` PyPI project from this directory:

```groovy
pip {
    options('--find-links', file('src/main/wheels').absolutePath)
    install 'libloot'
}
```

The wheel must target the same Python version as the Chaquopy configuration
(currently Python 3.13) and Android `arm64-v8a`.

Rebuild it with:

```sh
./scripts/build-libloot-wheel.sh
```

The script pins libloot to the version whose metadata matches the bundled
masterlist branch (`v0.29`). If you change either version, update the other.
