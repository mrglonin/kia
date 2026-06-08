# Signing

`kia-debug-release.keystore` is intentionally public.

It is the standard Android debug certificate used here only so public builds can
keep the same signature when rebuilding release APKs from this local tree.

Current public certificate:

```text
C=US, O=Android, CN=Android Debug
SHA-256: 72631978082200032bd33700f86195786e63a5ddb43166d186baa934c0942ca7
```

Do not use this key as a private production key. If you fork the project and
want private updates, generate your own keystore and change
`app/app/build.gradle`.
