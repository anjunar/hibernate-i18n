# Independent Hibernate I18n consumer

This sbt build consumes `com.anjunar:hibernate-i18n:1.1.0-SNAPSHOT` as an ordinary
dependency. It does not use `ProjectRef` or sources from the library build.

After this development version is published to a configured snapshot repository, run from this directory:

```shell
sbt --server "runMain RuntimeSmoke"
```

The smoke program starts embedded PostgreSQL and checks German and English
domain loading, exact-locale editor access and copying inactive translations
into a draft, using only annotated mappings and no manual field registration.
No application database is required.

Before publishing a release, the repository's `scripts/runtime-smoke.ps1`
installs the packaged artifacts locally and runs the same consumer.
