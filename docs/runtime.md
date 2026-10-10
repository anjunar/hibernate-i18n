# Hibernate I18n 1.1.0 runtime

The coordinate is `com.anjunar:hibernate-i18n:1.1.0`, without a Scala
suffix. Use the same runtime for application and production bootstrap; a separate
Development artifact is no longer needed. The supported ORM version is
Hibernate 7.4.10.Final, with PostgreSQL and JDK 17 or newer.
Declare translations only with `@Localized`, `@Translation` and the standard JPA annotations.
There is no second list of fields, ID readers, property accessors or conversion functions to maintain.

## Metadata and schema startup

Build the registry through `HibernateI18n.registryBuilder()`, add every annotated
entity to `MetadataSources`, and build metadata. This installs the mapping
contributors and late metadata finalizer. The generator uses classless internal
translation rows, formula-backed domain properties, physical table/column names,
the translation query-space and cascading foreign keys.

When Hibernate DDL Manager owns the schema, set `hibernate.hbm2ddl.auto=none`.
Give the concrete localized entity and every translated member stable eight-digit
`@SchemaId` values. Call `HibernateSchemaMigration.migrate(metadata, dataSource)`
after metadata build and before SessionFactory creation. The runtime provides the
generated rows' schema IDs and brings schema-integration 1.2.0 transitively.

`HibernateI18n.openSession` registers every mapped `@Localized` entity before opening
the first localized Session. Identifiers, field/getter access and conversion functions
come from the finalized Hibernate mapping. Applications maintain only the annotations.

Exact-locale editors obtain their handle without listing fields:

```scala
import com.anjunar.hibernatei18n.runtime.HibernateI18n

val translations = HibernateI18n.translations(factory, classOf[Page])
```

`fieldNames` exposes the annotation-derived inventory for generic editors and seeds.
Named access checks domain types and rejects untranslated properties.
Registration and handle lookup are idempotent. One factory listener dispatches each
flush to its entity bridges, and closing the factory removes its runtime registration.
Generic forms and multilingual seeds can use `setActive(session, entity, name, value)`
to change an annotated domain property through Hibernate's configured setter. This
needs no parallel list of setter functions. Ordinary domain code still assigns its
typed property directly. `setActive` uses the same managed-entity and field-type checks
as the editor; the resulting domain change is synchronized during normal flush.

Close the SessionFactory and destroy its StandardServiceRegistry during shutdown.
A plain Hibernate registry without the I18n bootstrap rejects annotation mappings
before they can put translated values in the parent table.

## Typed fields and naming

String members retain their ordinary type. A converted value must map to a String
database value. The runtime uses Hibernate's configured JPA converter instance in
both directions:

```scala
@Translation
@Convert(converter = classOf[MarkdownConverter])
var content: Markdown = uninitialized
```

No separate codec or reader is needed. Nulls are handled before conversion. `@Translation(column = ...)`
selects a translation column, with the physical naming strategy applied afterwards.
`@Column` on translated attributes is rejected. Physical collisions, including with
the row-version column, are detected during metadata build.

Field and JavaBean getter access are supported; mixed access is rejected. The
ID is a UUID and database values are Strings. Column identifiers must be simple;
table names may contain `#` and are quoted when needed. ID, version and String
tenant fields can be inherited from mapped superclasses. Translated members of a
mapped superclass may feed one entity mapping per factory. In a supported
single-table hierarchy, the owning localized entity is registered once; subclasses
inherit that bridge. Do not add a second localized owner for the same field.

## Locale and queries

Each Session fixes one content locale for its entire lifetime, including after
`clear()`. Use `HibernateI18n.openSession(factory, "de")` and a new Session for
another locale. Ordinary managed properties read fallback and write the active
locale. New entities use `persist`; load existing entities in the target locale
before editing. Detached `merge` and `replicate` are rejected.

Fallback is resolved separately for each field: exact locale, language, configured
fallback, then default locale. A changed title does not materialize another field's
fallback as an explicit override. Setting a field to null removes its active-locale
override; refresh or a new Session then reads fallback. The last cleared field
removes the row.

HQL and Criteria predicates, ordering and paging use the same locale-resolved
property. Auto-flush exposes translated writes according to the Session's flush
mode. Use transactions, roll back errors and always close Sessions.

## Exact-locale editor

The handle's `get` and `set` use a managed entity from that factory and the name of
an annotated property. No additional field definition is needed:

```scala
val english: Option[Markdown] =
  translations.get[Markdown](session, page, "content", "en")
translations.set(session, page, "content", "en", Markdown("**English**"))
```

`get` reads only the exact override, with no fallback. `set` accepts inactive
locales; active-locale writes belong to the entity property. Null clears a value.
An inactive editor write leaves already loaded domain state intact. If it changes
a fallback provider, refresh clean state or use a new Session to see that fallback.
Scalar HQL after auto-flush can already read the updated row in the current Session.

For a draft, persist a different-ID target with its active-locale fields and call
`translations.copyInactive(session, source, draft)` in the same transaction. It
copies raw stored values except the active locale, replaces matching target rows,
keeps target locales absent from the source, and returns the copied row count.
It does not update either entity's already loaded fields.

## Lifecycle, tenant and cache

Translation rows have optimistic versions. Concurrent edits of the same locale
row, including editor versus domain writes, reject the stale commit. Different
locale rows can commit independently. `refresh` adopts fresh values and row
versions, but refuses unflushed row changes. `clear()` and `evict()` discard field
snapshots so later loading reads fresh state.

For a shared-table entity with String `@TenantId`, use:

```scala
val session = HibernateI18n.openSession(factory, "de", "tenant-a")
```

Loading, HQL, editor operations and translation copies stay within the Session
tenant. The generated row carries the tenant column and a cascading foreign key
to the parent's ID/tenant key. Parent deletion removes its translation rows;
HQL bulk deletion leaves managed objects stale, so clear the Session afterwards.

Query caching is rejected. Metadata build disables second-level caching for
localized entity hierarchies even when they have cache annotations. Unrelated
entities can retain their cache. These restrictions prevent reuse of one locale's
domain state in another locale.

## Existing databases

The generated table, column names and stable schema IDs stay compatible with the
1.0.0 mapping. Legacy explicit translation entities remain separate;
the runtime does not automatically copy their rows into generated tables.

Generated String columns use PostgreSQL `text`. For older generated varchar
columns, call `TranslationSchemaUpgrade.widenTextColumns(metadata, dataSource)`
before building the factory. Its auto-commit DataSource is used for a PostgreSQL
transaction that preserves values, widens only generated value columns, skips
missing tables and existing text columns, and rejects unexpected types.

An existing database without DDL-manager history can be adopted only when its
complete schema exactly matches metadata. Prepare old text columns first, then:

```scala
import com.anjunar.hibernateddl.executor.ExecutionOptions
import com.anjunar.hibernateddl.integration.HibernateSchemaMigration

HibernateSchemaMigration.migrate(metadata, migrationDataSource,
  ExecutionOptions(adoptExistingSchema = true))
```

For Hibernate-generated checks with different names, review the statements from
`HibernateSchemaMigration.planHibernateCheckRenames(metadata, migrationDataSource)`
and apply the required renames explicitly before adoption. The planning probe
rolls back and does not write schema history. Adoption validates tables, columns,
keys, sequences and checks before recording its baseline, preserving existing rows.
