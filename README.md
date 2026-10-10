# Hibernate I18n

Relational translations for ordinary typed Hibernate entity fields. The entity keeps its identity and domain
types; translated values live in separate locale rows. Loading, queries and edits share one content locale per
Session.

| Version | Platform | Scala | Hibernate | License |
| --- | --- | --- | --- | --- |
| 1.1.0-SNAPSHOT | JVM / Java 17+ | 3.9 | 7.4.10.Final | MIT |

Documentation: [English](https://docs.anjunar.com/en/hibernate-i18n) · [Deutsch](https://docs.anjunar.com/de/hibernate-i18n)
Website: [English](https://anjunar.com/en/hibernate-i18n) · [Deutsch](https://anjunar.com/de/hibernate-i18n)

## Installation

The annotation-driven bootstrap below is new in the unreleased 1.1.0 development version.
The published 1.0.0 API still requires explicit field bridges.

One runtime artifact, without a Scala suffix. It brings `hibernate-i18n-core`, Hibernate ORM 7.4.10.Final and
[Hibernate DDL Manager](https://github.com/anjunar/hibernate-ddl-manager) `schema-integration` 1.2.0 transitively.
The application provides a PostgreSQL DataSource and JDBC driver. The runtime is built with Scala 3.9 for Java 17
or newer; the public `@Localized` and `@Translation` annotations are Java.

```scala
libraryDependencies += "com.anjunar" % "hibernate-i18n" % "1.1.0-SNAPSHOT"
```

```xml
<dependency>
  <groupId>com.anjunar</groupId>
  <artifactId>hibernate-i18n</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

## First example

`@Localized` declares the entity's fallback policy; `@Translation` marks the fields stored in locale rows.
The title remains a `String`, and the runtime generates the internal translation mapping. No translation entity
or composite-key class is needed. Stable `@SchemaId` values let the DDL manager track tables and columns across
renames.

```scala
import com.anjunar.hibernatei18n.annotation.{Localized, Translation}
import com.anjunar.hibernateddl.hibernate.annotation.SchemaId
import jakarta.persistence.{Entity, Id, Table}
import java.util.UUID
import scala.compiletime.uninitialized

@Entity
@Table(name = "page")
@SchemaId("7f3a9c21")
@Localized(defaultLocale = "en", fallbackLocale = "en")
class Page:
  @Id @SchemaId("0a1b2c3d") var id: UUID = uninitialized
  @Translation @SchemaId("f34e45b6") var title: String = uninitialized
```

Build the registry through `HibernateI18n` and migrate the complete metadata. Opening the first localized
Session registers every mapped `@Localized` entity automatically, using its `@Translation` members, Hibernate
property access and JPA converters. Here the DDL manager owns the schema and Hibernate schema generation is
disabled. `dataSource` is the application's configured PostgreSQL DataSource.

```scala
import com.anjunar.hibernatei18n.runtime.HibernateI18n
import com.anjunar.hibernateddl.integration.HibernateSchemaMigration
import org.hibernate.boot.MetadataSources

val registry = HibernateI18n.registryBuilder()
  .applySetting("hibernate.connection.datasource", dataSource)
  .applySetting("hibernate.hbm2ddl.auto", "none")
  .applySetting("hibernate.cache.use_query_cache", "false")
  .build()
val metadata = new MetadataSources(registry)
  .addAnnotatedClass(classOf[Page])
  .buildMetadata()
HibernateSchemaMigration.migrate(metadata, dataSource)
val factory = metadata.buildSessionFactory()
val translations = HibernateI18n.translations(factory, classOf[Page]) // Only needed for exact-locale editing.
```

Load an existing page in German, edit its ordinary field and write an exact English override through the typed
editor. `pageId` identifies that existing page. The English edit leaves its loaded German title intact.

```scala
val session = HibernateI18n.openSession(factory, "de")
try
  val tx = session.beginTransaction()
  try
    val page = session.find(classOf[Page], pageId)
    page.title = "Hallo"
    val english: Option[String] =
      translations.get[String](session, page, "title", "en")
    translations.set(session, page, "title", "en", "Hello")
    tx.commit()
  catch
    case error: Throwable =>
      if tx.isActive then tx.rollback()
      throw error
finally session.close()
```

`get` reads only the exact override, with `None` for a missing value. Ordinary entity fields apply fallback
separately: exact locale, language, configured fallback, then default locale. `set` accepts inactive locales;
write the active locale through the entity field. Close the SessionFactory and destroy the registry at shutdown.

## The principle

**01 / Fields – Keep the domain types.** Strings and converted values remain ordinary entity properties.
Their translated values belong to relational locale rows, with no translated value column on the parent.

**02 / Locale – One Session, one language.** The content locale is immutable. Loading, HQL and Criteria resolve
the same per-field fallback, including predicates, ordering and paging. Open a new Session for another locale.

**03 / Editor – Edit exact overrides.** A typed handle reads and writes individual inactive locales while the
managed entity keeps its loaded state. Translation rows have optimistic versions: competing edits to the same
row reject a stale commit, while different locale rows remain independently editable.

## Contents

Start with the entity mapping and bootstrap, then follow the Session, editor and schema guides.

**Basics**

- [Entity mapping](https://docs.anjunar.com/en/hibernate-i18n/mapping) – translated fields, field or getter access and converted values with a String database representation
- [Runtime bootstrap](docs/runtime.md#metadata-and-schema-startup) – registry, metadata, schema preparation and automatic translation registration
- [Locales and queries](https://docs.anjunar.com/en/hibernate-i18n/sessions) – a fixed Session locale, per-field fallback, managed edits and localized HQL and Criteria

**Editing**

- [Translation editor](https://docs.anjunar.com/en/hibernate-i18n/editor) – read exact overrides and edit inactive locales without replacing loaded fields
- [Drafts and copies](https://docs.anjunar.com/en/hibernate-i18n/drafts) – persist a draft's active language, then copy stored inactive translations

**Operations**

- [Schema integration](https://docs.anjunar.com/en/hibernate-i18n/schema) – stable IDs for generated rows, the DDL manager, text-column upgrades and existing databases
- [Session lifecycle](https://docs.anjunar.com/en/hibernate-i18n/lifecycle) – refresh, optimistic conflicts, tenant isolation and deletion

**Reference**

- [API and limits](https://docs.anjunar.com/en/hibernate-i18n/reference) – public runtime methods, supported mappings and rejected operations

## Limits

The 1.1.0 development version supports Hibernate ORM 7.4.10.Final and PostgreSQL. The mapping and lifecycle constraints are part
of its runtime contract.

- **Mapping:** one UUID ID, String database values and consistent field or JavaBean getter access. Converted
  values reuse the mapped JPA converter. Translated mapped-superclass members
  may feed one entity mapping per factory; a supported single-table hierarchy inherits its owning entity's bridge.
- **Bootstrap:** `openSession` automatically registers every mapped localized entity. `install(factory)` can
  register them eagerly. A plain Hibernate registry rejects translation annotations before schema generation.
- **Managed edits:** new entities use `persist`; load existing entities in the target locale before editing.
  Detached `merge` and `replicate`, mixed access and `@Column` on a translated property are rejected.
  Use `@Translation(column = ...)` to name its column.
- **Overrides:** `null` clears a field's override; clearing the last value removes its locale row. Refresh or a
  new Session observes fallback changes. Editor operations require a managed entity and resolve the field
  through the handle's name lookup or its `field[V](name)` accessor.
- **Copies:** `copyInactive(session, source, draft)` copies raw stored rows except the active locale and returns
  their count. Both entities must be managed and have different IDs. Set the draft's active fields first;
  matching target rows are replaced, target locales absent from the source remain, and loaded fields stay intact.
- **Tenants and deletion:** `openSession(factory, locale, tenantId)` isolates shared-table entities with a String
  `@TenantId`, including editor operations and copies. Parent deletion cascades to translation rows. Clear the
  Session after HQL bulk deletion before reloading affected objects.
- **Refresh and caching:** `refresh` reloads clean field and row-version state but rejects unflushed row changes.
  Query caching is rejected; second-level caching is disabled for localized entity hierarchies. Unrelated
  entities may remain cached.
- **Existing databases:** adoption requires the complete schema to match metadata. Older generated `varchar`
  value columns need the explicit text upgrade first. Legacy explicit translation rows are not copied
  automatically.

The repository's [runtime guide](docs/runtime.md) includes naming, conversion, schema adoption and upgrade
details. Earlier investigations are preserved in the [architecture report](docs/architecture.md).

## Modules

| sbt project | Artifact | Purpose |
| --- | --- | --- |
| `i18nCore` | `com.anjunar:hibernate-i18n-core` | Java marker annotations |
| `i18nHibernate` | `com.anjunar:hibernate-i18n` | Mapping, Session runtime, typed editor and schema integration |
| `i18nTests` | not published | Additional PostgreSQL/Hibernate integration experiments |

Package root: `com.anjunar.hibernatei18n`. There is one public runtime; the former separate Development profile
is removed. The aggregate build and test module are not published.

## Development

Requirements: JDK 17 or newer and sbt 2. Pinned versions: Scala 3.9.0, sbt 2.0.9, Hibernate ORM 7.4.10.Final and
MUnit 1.2.0.

```shell
sbt --server check
sbt --server publishLocal
```

`check` performs a clean build, runs every test with `testFull`, and verifies the
published runtime JAR's public API, bootstrap guard and schema-ID provider. Tests
start temporary PostgreSQL clusters. sbt 2's `test` is incremental and may replay
unchanged tests from its cache. `HIBERNATE_I18N_TEST_POSTGRES` selects an
external test database for the integration experiments. CI covers JDK 17 with
embedded PostgreSQL and JDK 25 with PostgreSQL 18. The independent
[runtime consumer](examples/runtime-consumer) checks the packaged artifacts.

### Releasing

DDL Manager 1.2.0 must be available on Maven Central before this runtime is released.
Run `check` and the independent consumer before signing and publishing.

```powershell
.\scripts\set-version.ps1 -Check
.\scripts\runtime-smoke.ps1
.\scripts\publish-central.ps1 -Version 1.0.0
```

The release scripts sign both artifacts with GPG and upload them to the Central
Portal. Credentials come from `SONATYPE_CENTRAL_USERNAME` and
`SONATYPE_CENTRAL_PASSWORD`, or `~/.sbt/sonatype_central_credentials`.

## License

Hibernate I18n is available under the [MIT License](LICENSE), Copyright (c) 2026 Patrick Bittner.
