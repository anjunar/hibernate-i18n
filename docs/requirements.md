> **Architektur-Klarstellung vom 3. Oktober 2026:** Die normale Domain-API verwendet
> weiter `page.getTitle()` und `page.getContent()` mit den ursprünglichen Java-Typen.
> Eine Hibernate-Session hat genau eine aktive Content-Locale, die für die Lebensdauer
> bereits verwalteter Entities nicht gewechselt wird. Eine explizite Translation-API
> ist nur für Editoren vorgesehen. Frühere Vorschläge wie `page.localized(locale)`,
> `page.translation(locale)` oder Property-Wrapper sind damit als normale API überholt.
> Der technische Stand und die noch offene Machbarkeit stehen in
> [architecture.md](architecture.md).

Ich möchte ein neues Hibernate-Modul entwickeln, das Mehrsprachigkeit auf ORM-Ebene ähnlich elegant integriert, wie Hibernate Envers Auditing integriert.

Das Ziel ist ein wiederverwendbares Modul, z. B.:

`anjunar-hibernate-i18n`

## Zielbild

Der Entwickler soll möglichst nur Folgendes schreiben müssen:

```java
@Entity
@Localized(defaultLocale = "de", fallbackLocale = "en")
public class Page {

    @Id
    private UUID id;

    private String slug;

    @Translation
    private String title;

    @Translation
    @Lob
    private String description;

    @Translation
    @Lob
    private String content;
}
```

Daraus soll das Hibernate-Modul automatisch ein relational sauberes Modell erzeugen, konzeptionell etwa:

```text
page
----------------
id
slug


page_translation
----------------
page_id
locale
title
description
content

PRIMARY KEY (page_id, locale)
FOREIGN KEY (page_id) REFERENCES page(id)
```

Der Entwickler soll **keine `PageTranslation`-Entity**, keinen Composite Key und möglichst keine manuelle Association definieren müssen.

---

# Wichtig: Erst analysieren, dann implementieren

Bitte untersuche zunächst die aktuelle Hibernate-Architektur und insbesondere, wie Hibernate Envers heute technisch integriert ist.

Analysiere dabei insbesondere:

- `AdditionalMappingContributor`
- `AdditionalMappingContributions`
- `Integrator`
- Hibernate Event Listener
- Bootstrap-/Metadata-Phase
- Runtime-/SessionFactory-Phase
- ServiceLoader-basierte Registrierung
- dynamisch erzeugte Entity-/Mapping-Metadaten
- Hibernate Runtime Metamodel
- Dirty Checking
- First-Level Cache
- Second-Level Cache
- Query-System / HQL / Criteria
- Schema Generation

Schau dir insbesondere die aktuelle Envers-Architektur an:

- Welche Verantwortung liegt beim Mapping Contributor?
- Welche Verantwortung liegt beim Integrator?
- Wie erzeugt Envers zusätzliche Tabellen und Mappings?
- Welche Architektur davon lässt sich sinnvoll auf ein I18n-Modul übertragen?

Bitte nicht blind Envers kopieren, sondern die zugrunde liegenden Erweiterungspunkte verstehen.

---

# Zentrale Architekturregel

Eine normale Hibernate Entity darf nicht abhängig von der aktuellen Sprache unterschiedliche persistente Identitäten bekommen.

Das heißt:

```text
Page#42
```

muss innerhalb einer Hibernate Session immer dieselbe Entity bleiben.

Folgende Zustände hingegen sind eigenständige persistente Datensätze:

```text
PageTranslation(42, de)
PageTranslation(42, en)
PageTranslation(42, fr)
```

Bitte berücksichtige insbesondere:

- Persistence Context Identity
- First-Level Cache
- Second-Level Cache
- Dirty Checking
- eine unveränderliche aktive Content-Locale pro Session

Ich möchte keine fragile Magie, bei der:

```java
page.getTitle()
```

einmal Deutsch und später Englisch zurückliefert, obwohl dieselbe Entity bereits im Persistence Context liegt.

Intern dürfen und sollen Übersetzungen daher eigene persistente Zustände besitzen.
`page.getTitle()` liefert innerhalb dieser Session dauerhaft den beim Laden
ermittelten Wert. Eine andere Content-Locale erfordert normalerweise eine neue Session.

---

# Gewünschte Entwickler-API

Der normale Zugriff verwendet unveränderte Java-Property-Typen und normale Getter:

```java
page.getTitle();
page.getContent();
```

Die aktive Content-Locale wird der Hibernate-Session vor dem ersten Load
zugeordnet und bleibt für diese Session unverändert. Ein `LocaleResolver` kann
die Locale beim Öffnen der Session liefern, darf aber bereits verwaltete Entities
nicht nachträglich umschalten.

Für Übersetzungseditoren kann eine **separate** API Zugriff auf andere Locales
derselben fachlichen Entity geben, etwa konzeptionell:

```java
translations.of(page).get(Page_.content, Locale.ENGLISH);
translations.of(page).set(Page_.content, Locale.ENGLISH, markdown);
```

Diese Editor-API ist nicht Voraussetzung für normale Domain-Getter und darf
deren verwalteten Zustand nicht umschalten.

Fallback:

```text
de-DE
 ↓
de
 ↓
en
 ↓
defaultLocale
```

Die Fallback-Strategie soll konfigurierbar sein.
Ein auf `null` gesetztes `@Translation`-Property entfernt den Wert der aktiven
Locale und gibt den Fallback wieder frei. `null` ist kein expliziter Override,
der den Fallback unterdrückt. Andere übersetzte Properties desselben Locale-
Rows bleiben erhalten.

---

# Annotationen

Mindestens:

```java
@Target(TYPE)
@Retention(RUNTIME)
public @interface Localized {
    String defaultLocale() default "";
    String fallbackLocale() default "";
}
```

und:

```java
@Target({FIELD, METHOD})
@Retention(RUNTIME)
public @interface Translation {
}
```

Untersuche sinnvolle Erweiterungen, z. B.:

```java
@Translation(nullable = false)
```

oder Übernahme vorhandener Hibernate/JPA-Annotationen:

```java
@Translation
@Lob
@Column(length = ...)
private String content;
```

Wichtig:

Bestehende Mapping-Informationen wie

- `@Column`
- `@Lob`
- nullability
- length
- SQL-Type
- Converter

sollten nach Möglichkeit auf die erzeugte Translation-Spalte übertragen werden.

---

# Bootstrapping

Meine aktuelle Vermutung ist:

```text
@Entity
@Localized
   ↓
AdditionalMappingContributor
   ↓
Translation-Metadata erzeugen
   ↓
zusätzliche Hibernate-Mappings
   ↓
Hibernate Runtime Metamodel
```

und:

```text
SessionFactory Bootstrap
   ↓
Integrator
   ↓
Services / Listener / Runtime Integration
```

Bitte prüfe kritisch, ob das wirklich die sauberste Architektur ist.

Wenn es modernere oder bessere Hibernate-SPIs gibt, verwende diese.

---

# Translation Mapping

Für:

```java
@Entity
@Localized
class Page {

    @Id
    UUID id;

    String slug;

    @Translation
    String title;

    @Translation
    String content;
}
```

soll intern konzeptionell etwas entstehen wie:

```text
PageTranslation

page_id
locale
title
content
```

mit Identity:

```text
(page_id, locale)
```

Untersuche, wie sich dieses Mapping erzeugen lässt, ohne dass der Benutzer eine konkrete Java-Klasse `PageTranslation` schreiben muss.

Mögliche Ansätze bitte vergleichen:

1. dynamische Hibernate Entity
2. generierte Runtime-Klasse
3. Bytecode Generation
4. Map-basierte Entity
5. hidden/generated Mapping Model
6. andere Hibernate-interne Mechanismen

Wähle die sauberste, stabilste und am wenigsten invasive Variante.

---

# Queries

Ein sehr wichtiger Punkt:

Wie sieht später eine Query aus?

Idealerweise bezieht sich auch eine normale Query auf den übersetzten
Wert der aktiven Session-Locale:

```java
select p
from Page p
where p.title like :query
```

Falls Hibernate diese transparente HQL-Semantik nicht sauber ermöglicht,
untersuche eine explizite Query-API, beispielsweise konzeptionell:

```java
translation(Page.class, "title", locale)
```

Untersuche zunächst, was realistisch und idiomatisch mit Hibernate machbar ist.

Zumindest folgende Fälle müssen möglich sein:

- Entity nach übersetztem Titel suchen
- nur eine bestimmte Locale abfragen
- Fallback optional anwenden
- Sortieren nach übersetztem Wert
- Paging
- JOINs
- Criteria API
- möglichst HQL-Unterstützung

Für Version 1 muss nicht jede denkbare Query-Syntax implementiert werden.

Entwirf aber eine Architektur, die später erweiterbar bleibt.

---

# Locale Resolver

Bitte ein kleines SPI vorsehen:

```java
public interface LocaleResolver {
    Locale resolve();
}
```

Das Modul selbst darf nicht von HTTP, Jakarta REST, JSF oder irgendeinem Webframework abhängen.

Andere Module können später Resolver bereitstellen, beispielsweise:

- RequestContext
- ThreadLocal
- CDI
- Jakarta REST
- SSR Request Context

Core muss unabhängig bleiben.

---

# Multi-Tenancy

Das Modul wird später in einem Multi-Tenant-System eingesetzt.

Die Translation gehört fachlich zur jeweiligen Entity.

Wenn die Parent Entity bereits tenant-isoliert ist, darf die Translation diese Isolation nicht umgehen.

Bitte auf mögliche Probleme mit:

- Tenant Filter
- Hibernate Multi-Tenancy
- Foreign Keys
- Queries

achten.

---

# DDL

Das Mapping muss so vollständig Teil des Hibernate-Metamodells werden, dass Schema-Tools die Translation-Tabellen sehen können.

Ich habe zusätzlich einen eigenen Hibernate DDL Manager.

Daher möchte ich später aus dem Mapping-Modell erkennen können:

```text
Page
PageTranslation
```

inklusive:

- Tabellen
- Spalten
- Datentypen
- Primary Keys
- Foreign Keys
- Unique Constraints
- Nullability
- LOB
- JSON
- Indizes

Keine Sonderlogik, die ausschließlich versteckt SQL erzeugt.

Das Translation-Modell soll möglichst echtes Hibernate-Metadata sein.

---

# Package-Vorschlag

Ungefähr:

```text
anjunar-hibernate-i18n

annotation/
    Localized
    Translation

boot/
    TranslationMappingContributor

integrator/
    TranslationIntegrator

metadata/
    LocalizedEntityMetadata
    TranslationPropertyMetadata
    TranslationMetadataRegistry

locale/
    LocaleResolver
    LocaleContext
    LocaleFallbackStrategy

runtime/
    TranslationAccessor
    ...
```

Bitte das Package Design selbst kritisch verbessern.

---

# Anforderungen an die Implementierung

- moderne Hibernate-Version berücksichtigen
- keine veralteten SPIs verwenden, wenn es bessere aktuelle APIs gibt
- möglichst wenig Zugriff auf fragile interne Hibernate-Implementierungsdetails
- Java Module sauber trennen
- thread-safe
- keine globale mutable Locale
- Hibernate Sessions dürfen nicht korrumpiert werden
- First-Level Cache Semantik erhalten
- Second-Level Cache berücksichtigen
- Lazy Loading berücksichtigen
- Dirty Checking muss funktionieren
- Inserts/Updates/Deletes müssen korrekt funktionieren
- Cascading beim Löschen der Parent Entity
- orphan cleanup
- Transaktionen beachten
- möglichst normale Hibernate-Mechanismen verwenden

---

# Testfälle

Bitte umfangreiche Integrationstests schreiben.

Mindestens:

## Mapping

```text
Page
title @Translation
content @Translation
```

erzeugt:

```text
page
page_translation
```

## Persist

Deutsch speichern:

```text
Page 42
de
Hallo Welt
```

Englisch ergänzen:

```text
Page 42
en
Hello World
```

## Read

In einer Session mit aktiver Locale `de` liefert `page.getTitle()` Deutsch.
In einer **anderen** Session mit aktiver Locale `en` liefert
`page.getTitle()` Englisch. Die Java-Property bleibt vom Typ `String`.

## Update

Nur englischen Titel ändern.

Deutsch darf nicht als dirty gelten.

## Missing Locale

```text
requested = de-DE
available = de
```

→ `de`

und:

```text
requested = fr
available = en
fallback = en
```

→ `en`

## Delete

Parent löschen:

```text
Page#42
```

→ alle Translation Rows werden ebenfalls entfernt.

## Session Identity

`Page#42` bleibt in einer Session dieselbe verwaltete Entity mit festem
Content-Locale-Wert. Ein Locale-Wechsel innerhalb derselben Session wird
abgelehnt. Der explizite Editor-Zugriff auf andere Translation-Rows darf
den geladenen Parent-Zustand nicht umschalten.

## Query

Suche nach translated title.

## DDL Metadata

Translation Table muss im Hibernate Metadata Model sichtbar sein.

---

# Vorgehensweise

Arbeite in dieser Reihenfolge:

1. Aktuelle Hibernate-SPIs analysieren.
2. Aktuelle Envers-Integration analysieren.
3. Architekturentscheidung dokumentieren.
4. Risiken identifizieren.
5. Minimalen Proof of Concept bauen.
6. Mapping einer einzigen Entity mit einem `@Translation` Property implementieren.
7. Persist/Load/Update/Delete testen.
8. Mehrere Properties unterstützen.
9. mehrere Locales unterstützen.
10. LocaleResolver + Fallback implementieren.
11. Queries untersuchen/implementieren.
12. API aufräumen.
13. ausführliche Tests.
14. Architektur dokumentieren.

---

# Wichtig

Bitte nicht zu früh abstrahieren.

Der erste Meilenstein sollte nur beweisen:

```java
@Entity
@Localized
class Page {

    @Id
    UUID id;

    @Translation
    String title;
}
```

erzeugt automatisch ein funktionierendes relationales Translation-Mapping.

Erst wenn Persist, Load, Update, Delete und Session-Semantik sauber funktionieren, die API erweitern.

Wenn eine gewünschte Idee mit Hibernate technisch problematisch ist, nicht mit Hacks erzwingen.

Dokumentiere stattdessen:

- Warum sie problematisch ist
- welche Hibernate-Invariante verletzt würde
- welche Alternative sauberer ist

## Endziel

Ich möchte am Ende ein Modul haben, das sich für Entwickler ungefähr so selbstverständlich anfühlt wie:

```java
@Audited
```

bei Envers.

Nur eben:

```java
@Localized
```

und:

```java
@Translation
```

für relationale, typsichere und sauber in Hibernate integrierte Mehrsprachigkeit.
