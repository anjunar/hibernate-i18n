# Übergabe an SOL: Hibernate I18n

Stand: 3. Oktober 2026. Die fachliche Spezifikation steht in
[docs/requirements.md](docs/requirements.md), der technische Befund in
[docs/architecture.md](docs/architecture.md), Build und Module in [README.md](README.md).
Die Architektur-Klarstellung am Anfang der Anforderungen ist verbindlich.

## Ziel

Die normale Domain-Entity behält ihre Feldtypen und Getter:

```java
@Entity
@Localized
class Page {
    @Id UUID id;
    @Translation String title;
    @Translation Markdown content;
}

page.getTitle();
page.getContent();
```

Eine Hibernate-Session hat genau eine aktive Content-Locale. Das Laden von
`Page#42` fixiert die übersetzten Werte dieser verwalteten Entity für die
Lebensdauer ihres Persistence Context. Eine andere Locale erfordert normalerweise
eine andere Session. Die Translation-Rows besitzen eigene persistente Identitäten
`(page_id, locale)`. Für Admin-Editoren darf es zusätzlich eine explizite
Translation-API geben. `page.localized(locale)`, `page.translation(locale)` und
Wrapper wie `Translated<Markdown>` sind **nicht** die normale Domain-API.

## Bisher umgesetzt

- Projekt unter `C:\Users\Patrick\IdeaProjects\hibernate-i18n`, Build-Konventionen
  aus dem Nachbarprojekt `hibernate-ddl-manager` übernommen.
- sbt 2.0.9, Scala 3.9.0, Java-Zielversion 17, Hibernate ORM/Envers 7.4.10.Final.
- Module `i18nCore`, `i18nHibernate`, `i18nDevelopment`, `i18nTests`; PostgreSQL-Fixture, CI,
  Release-Skripte, MIT-Lizenz und Git-Repository.
- Marker-Annotationen `@Localized` und `@Translation` in `i18n-core`.
- ServiceLoader-registrierter Guard in `i18n-hibernate`: Annotationen werden
  beim Metadata-Build mit verständlichem Fehler abgelehnt, solange keine sichere
  persistente Integration existiert. Er verhindert ein falsches Parent-Tabellen-
  Mapping und erzeugt selbst keine Translation-Tabelle.
- Das separat lokal veröffentlichte `i18n-development` bietet einen expliziten
  Entwicklungs-Bootstrap und eine Session-API. Es nutzt die internen XML- und
  Metadata-Bausteine, installiert einen generischen Teststand-Listener für
  angegebene Translation-Properties und öffnet Sessions mit fester Locale.
  Ein eigenständiger PostgreSQL-Verbrauchertest belegt normales `String`- und
  konvertiertes `Markdown`-Laden, Schreiben, HQL und Fallback. Der reguläre
  `i18n-hibernate`-Bootstrap bleibt fail-closed. Nutzung und Grenzen stehen
  in [docs/development.md](docs/development.md). Ein separates Beispielprojekt
  unter `examples/development-consumer` kompiliert gegen das lokal publizierte
  `i18n-development_3:0.1.0-SNAPSHOT`.
  `DevelopmentI18n.install` liefert jetzt eine typisierte Editor-API zurück.
  Ein PostgreSQL-Test liest, setzt und entfernt damit englische `String`- und
  `Markdown`-Werte in einer deutschen Session, ohne die verwaltete Page zu
  verändern; eine neue englische Session sieht den Wert bzw. nach dem Entfernen
  wieder den Fallback. Änderungen an der aktiven Locale gehen weiterhin über
  das normale Domain-Feld. Der dreiparametrige `converted`-Aufruf bleibt für
  Write-only-Nutzung erhalten; typisierte Editor-Reads brauchen den Decoder.
  Weitere PostgreSQL-Fälle für dieses veröffentlichte Development-Profil
  bestätigen optimistische Konflikte zweier Editoren derselben Locale und
  zwischen Editor und Domain-Schreibpfad. Verschiedene Locale-Rows lassen
  sich parallel ändern. Anlegen und Entfernen eines Overrides innerhalb
  einer Session hinterlässt keinen Row.
  Eine zusätzliche `openSession(factory, locale, tenantId)`-Überladung bindet
  für String-`@TenantId`-Entities den Mandanten an dieselbe Session. Ein eigener
  PostgreSQL-Test für das veröffentlichte Development-Profil prüft `find`,
  HQL und Editor-Zugriffe über zwei Mandanten, weist ein fremdes detached
  Page-Objekt ab und lässt beim Delete die Daten des anderen Mandanten intakt.
  Ein Zwei-Entity-Test fand eine bisherige Bootstrap-Lücke: Hibernate verwirft
  den zweiten Listener derselben Klasse als Duplikat. Das Development-Profil
  registriert deshalb jetzt einen Listener-Hub pro SessionFactory und leitet
  Events an die Brücken aller installierten Entity-Typen weiter. `openSession`
  verweigert den Start, solange für eine gemappte `@Localized`-Entity keine
  Brücke installiert ist. Der PostgreSQL-Test prüft Laden, getrennte Writes
  und einen gemeinsamen Flush beider Entity-Typen.
  Ein weiterer Development-Test prüft die Session-Invariante bei einem aus
  `en` geerbten Wert: Ein explizites Editor-Update des englischen Rows lässt
  die schon verwaltete deutsche Page unverändert; HQL-Skalarwerte nach Auto-
  Flush und eine neue Session sehen den neuen Fallback. Der Development-
  Synchronizer vergleicht zudem jeden manuell gelieferten Field-Reader beim
  Flush mit Hibernates gemapptem Property-Wert und bricht bei Fehlverdrahtung
  ab. Ein Negativtest belegt diesen Fehlerpfad.
  Ein weiterer PostgreSQL-Test für das veröffentlichte Development-Profil
  bestätigt JavaBean-Getter-Zugriff mit `String` und konvertiertem `Markdown`,
  normale Setter-Writes, HQL-Auto-Flush und typisierte Editor-Zugriffe. Er
  prüft außerdem `@Translation(column = "displayTitle")` am Getter und das
  Fehlen einer Titelspalte in der Parent-Tabelle.
  Das Development-Profil weist Query-Caching beim Metadata-Build weiterhin ab.
  Für eine lokalisierte Entity mit `@Cacheable`/Hibernate `@Cache` oder JPA-
  `sharedCache.mode=ALL` deaktiviert es den Second-Level-Cache der gesamten
  Entity-Hierarchie. Ein PostgreSQL-Test mit speicherndem In-Memory-Provider
  prüft getrennte deutsche und englische Werte sowie Cache-Hits für eine
  unabhängige Entity. Damit ist die `@Cache`-Annotation von `ScheduleEvent`
  kein Bootstrap-Blocker mehr; der Parent-State wird nicht gecacht.
  Ein weiterer PostgreSQL-Test prüft im veröffentlichten Development-Profil
  `refresh`, `clear()` und `evict()`: externe Änderungen der aktiven Locale
  und des Fallbacks werden nach einem Reload sichtbar, Folgeschreibvorgänge
  behalten die aktuelle Row-Version, und ein Refresh mit ungespeicherter
  Änderung am verwalteten Translation-Row wird abgewiesen.
  Für die Stack-Hierarchie erkennt `LocalizedEntityMembers` jetzt die UUID-ID
  und den String-`@TenantId` auf `@MappedSuperclass`-Vorfahren. Das XML-Overlay
  setzt den Feld-/Getter-Zugriff explizit. Ein PostgreSQL-Test belegt eine
  geerbte `val id` plus `@Version` und Tenant sowie eine `SINGLE_TABLE`-
  Text-Entity mit `@Translation` und eine Markdown-Unterklasse, die denselben
  übersetzten Wert erbt. Installiert wird nur die annotierte Text-Entity.
  Derselbe Test belegt jetzt auch `@Translation` auf einer `@MappedSuperclass`
  zusammen mit einem lokalen Translation-Feld: German/English-Laden,
  Fallback und Schreiben funktionieren, die Parent-Tabelle erhält keine
  Wertspalte. Ein separater Metadaten-Test bindet einen geerbten JavaBean-
  Getter als Formel. Die gemeinsame Basisklasse darf nur von einem Entity-
  Mapping derselben SessionFactory verwendet werden; mehrfach verwendete
  und unlokalisierte geerbte Translation-Felder scheitern beim Bootstrap.
  Stack-Tabellennamen wie `Offerings#Offering` und `Schedule#Event` werden
  im Development-Profil nun beim Erzeugen der Translation-Tabelle sicher
  gequotet. Der dynamische Map-Entity-Name nutzt einen internen Namespace
  samt vollständigem Parent-Klassennamen statt `OfferingTranslation` o. Ä.
  Ein PostgreSQL-Test mit Camel-Case-Naming-Strategie lässt die vorhandenen
  Legacy-`*Translation`-Entities gleichzeitig gemappt, persistiert beide
  Row-Arten und prüft Locale-Laden, Schreiben sowie HQL-Auto-Flush. Die
  `Schedule#Event`-Fixture trägt jetzt zusätzlich `@Cacheable` und Hibernate
  `@Cache`; der kombinierte Test prüft, dass ihr Parent-State uncached bleibt.
  `scripts/dev-smoke.ps1` publiziert das aktuelle SNAPSHOT lokal und startet
  `examples/development-consumer` mit eingebettetem PostgreSQL. Der unabhängige
  Consumer persistiert eine deutsche Page, schreibt Englisch über die Editor-
  API und prüft normale Lesezugriffe beider Locales. Der komplette Befehl lief
  erfolgreich mit `development-smoke: de=Hallo, en=Hello, editor=Hello`.
  Der reale Stack-Test `AnnotatedContentMappingSpec` bindet mit diesem Snapshot
  alle sieben lokalisierten Entity-Typen und ihre Legacy-Translation-Entities.
  Ein weiterer PostgreSQL-Test startet mit befüllten `Schedule#Event`- und
  Legacy-Tabellen und bestätigt, dass Hibernate `hbm2ddl.auto=update` die
  generierte Translation-Tabelle ergänzt, ohne die alten Rows zu ersetzen.
  Dies entspricht dem aktuellen Stack-Development-Default. Der optionale
  `hibernate-ddl-manager` 1.1.0 kann diese klassenlosen Map-Entities dagegen
  noch nicht lesen: `HibernateSchemaSource.read` wirft eine NPE bei
  `getMappedClass`/`@SchemaId`. Ein Laufzeittest beweist zusätzlich, dass eine
  Metadata-Sicht ohne generierte Entities nicht genügt: Nach separater Anlage
  der Translation-Tabelle mit Cascade-FK verweigert der Manager die zweite
  Migration wegen eines Fremdschlüssels einer unmodellierten Tabelle auf die
  verwaltete Parent-Tabelle. Die notwendige FK-Struktur darf nicht entfallen;
  für eine DDL-Manager-Migration braucht es eine explizite stabile Schema-
  Identität der generierten Tabelle und Spalten. Zusätzlich kann das aktuelle
  DDL-Manager-Modell weder den Tenant-FK auf `(parent_id, tenant_id)` noch
  `ON DELETE CASCADE` darstellen. Für Development bleibt der Manager deaktiviert
  und Hibernate `update` aktiv.
  Neue generierte String-Wertspalten werden als PostgreSQL `text` angelegt.
  Der explizite `DevelopmentSchemaUpgrade.widenTextColumns(metadata, dataSource)`
  erweitert vor dem SessionFactory-Bau alte `varchar(255)`-Wertspalten
  transaktional, ohne vorhandene Werte zu verlieren. Hibernates `update`
  erledigt diese Typänderung nicht selbst. Ein PostgreSQL-Test prüft den
  Upgrade-Pfad und Werte mit 60.000 Zeichen.
- Architekturbericht mit untersuchten Hibernate-/Envers-SPIs, Prototyp-Befund,
  Risiken und nächsten Wegen. Eine freigegebene Produktionsintegration existiert
  noch nicht.
- Isolierter Versuch im Testmodul: XML-`<formula>` lässt `title` und
  `Markdown content` mit ihren ursprünglichen Scala-Typen im Hibernate-State.
  Ein fester SQL-Inspector je Session bindet die Locale; HQL filtert nach
  `p.title` und `p.content`. Eine intern gemappte Scala-Translation-Entity
  besitzt den Composite Key `(pageId, locale)`, FK und Delete-Cascade.
  Vorangestellte Flush-/AutoFlush-Listener synchronisieren Änderungen von
  `String title` und `Markdown content`; Hibernate schreibt die Rows.
  `<synchronize>` im XML-Overlay macht HQL-Auto-Flush korrekt. Listener,
  Translation-Entity und Overlay sind noch **nicht produktiv** oder
  automatisch aus `@Translation` erzeugt.
- Zweiter isolierter PostgreSQL-Versuch: Hibernates erweitertes `mapping.xml`
  legt eine klassenlose Map-Entity für Translation-Rows mit `(page_id, locale)`
  an. Die Formula-Page behält normale `String`-/`Markdown`-Felder. Ein
  testinterner Flush-Listener synchronisiert die Map-Rows; Locale-Laden,
  HQL-Auto-Flush, Insert, Update, Rollback und Delete-Cascade funktionieren
  im geprüften Fall. Der FK wird vor dem SessionFactory-Bau über Hibernate-
  Metadata erzeugt. Der Versuch ersetzt keine produktive Annotation-Integration.
- Beide Test-Listener erkennen Änderungen der normalen Felder gegenüber dem
  geladenen oder zuletzt synchronisierten Zustand. Ein anschließender direkter
  Editor-Update am Translation-Row wird beim nächsten Flush nicht mit dem
  alten Wert der verwalteten Page überschrieben. Die Page selbst wechselt ihren
  Wert dabei nicht; eine neue Session lädt den Editorwert.
- Testinterne ServiceLoader-Contributors erzeugen aus einer registrierten
  `@Localized`-Scala-Entity mit `@Translation`-Feldern das frühe XML für
  Formeln, Query-Space und klassenlose Map-Entity sowie später den FK. Der
  isolierte PostgreSQL-Test belegt normales Laden und HQL für `String` und
  `Markdown`, Composite Key, fehlende Parent-Spalten und Delete-Cascade.
  Der generische testinterne Map-Listener ergänzt im selben Test HQL-Auto-
  Flush, Änderungen beider Felder, Insert, direkten Editor-Update und Rollback.
  Die Fallback-Formel priorisiert exakte Locale, Sprache, `fallbackLocale`
  und `defaultLocale` je Feld. Der Test belegt `de-DE → de`, `fr → en` und
  den Default `de`, wenn `en` fehlt. Reines Lesen erzeugt keinen neuen Row.
  Ändert `fr` nur den geerbten Titel, bleibt `content` im französischen Row
  null und folgt späteren Änderungen des englischen Content.
  `null` ist nun als Entfernen eines Locale-Overrides festgelegt. Ein reines
  Null-Setzen ohne Row legt keinen leeren Row an; das Leeren des letzten Werts
  löscht den Row; andere übersetzte Felder bleiben erhalten. `refresh()` oder
  eine neue Session zeigt danach den Fallback. Direkt nach der Zuweisung
  bleibt das verwaltete Feld bis zum Reload null.
  Allein in diesem Test filtert ein eigener Bootstrap-ClassLoaderService den
  produktiven Guard; normale Annotation-Builds werden weiterhin abgewiesen.
  XML-/Formelgenerator und später Metadaten-Finalizer liegen nun intern in
  `i18n-hibernate` und werden von testinternen ServiceLoader-Contributors
  aufgerufen. Sie unterstützen derzeit Feld- oder JavaBean-Getter-Zugriff,
  UUID-ID und String-Datenbankwerte. Ein isolierter PostgreSQL-Test belegt
  getterbasierte `String`- und per `@Convert` konvertierte `Markdown`-Lese-/HQL-Pfade
  samt Fallback. Der testinterne Map-Listener schreibt auch getterbasierte
  Inserts und Updates in die aktive Locale-Zeile, einschließlich HQL-Auto-Flush
  einer neuen Page und Entfernen eines leeren Overrides. Ein weiterer Test
  belegt `refresh()` nach externem Row-Update, die Ablehnung von Refresh bei
  ungespeicherten Editor-Änderungen, Snapshot-Reset bei `clear()`/`evict()`
  und die Ablehnung von detached `merge` in derselben und einer anderen Locale.
  Die Getter-Pfade prüfen auch Hibernates Flush-Modi: `COMMIT` schreibt erst
  beim Commit, `MANUAL` nur bei explizitem `flush()`, `ALWAYS` vor der HQL-Abfrage.
  `replicate()` wird ebenfalls abgewiesen. Beim Parent-Delete werden geladene
  Translation-Rows vor dem Datenbank-Cascade evicted. Konkurrenz zwischen zwei
  Writes derselben Locale führt zu einem Stale-Write-Fehler; Writes in zwei
  verschiedenen Locales bleiben unabhängig. Der testinterne localegetrennte
  Query-Cache hält auch getterbasierte Skalar- und Entity-Abfragen getrennt und
  invalidiert sie nach Row-Änderung. Dieser Nachweis nutzt weiterhin nur den
  In-Memory-Provider; der Parent-Entity-Cache bleibt deaktiviert. Gemischter Feld-/Getter-Zugriff
  scheitert beim Bootstrap.
  Die Contributor-Hüllen, Cache-Freigabe und alle Laufzeit-
  Listener bleiben testintern. Der produktive ServiceLoader registriert
  weiterhin nur den Guard; diese internen Bausteine aktivieren keine
  Annotation-Mappings. Die Fallback-Priorität ist
  fest; eine konfigurierbare Strategie fehlt noch.
- Der späte testinterne Metadatenbeitrag baut die Formeln nun aus den
  tatsächlich gebundenen physischen Tabellen- und Spaltennamen neu auf und
  ergänzt den physischen Translation-Query-Space. PostgreSQL prüft Snake Case
  und eine eigene Tabellen-/Spaltenpräfix-Strategie samt Laden, HQL-Filter und
  Query-Cache-Invalidierung. Eine quotierende Naming-Strategie mit
  großgeschriebenen Tabellen- und Spaltennamen besteht jetzt dieselben
  PostgreSQL-Pfade; die Formeln verwenden Hibernates gebundene Namen mit
  Dialekt-Quoting. Auch die spät ergänzte `row_version`-Spalte folgt nun der
  physischen Naming-Strategie statt immer unquotiert `row_version` zu heißen.
  `@Column` direkt an `@Translation` wird im engen Prototyp
  beim Bootstrap abgewiesen, weil es im XML-Overlay sonst die Formel verdrängt.
  Ein optionales `@Translation(column = "displayTitle")` benennt stattdessen
  die Spalte im Translation-Row. Für Feld- und JavaBean-Getter-Zugriff sind
  Snake-Case-Benennung, fehlende Parent-Spalte, Laden und HQL mit PostgreSQL
  geprüft. Doppelte Namen und Kollisionen mit internen Schlüsselspalten
  werden beim Bootstrap abgewiesen, auch wenn erst die physische Naming-
  Strategie unterschiedliche logische Namen zusammenführt (`pageId` /
  `page_id`, `rowVersion` / `row_version`, `displayTitle` / `display_title`).
  Geerbte `@Translation`-Member aus einer `@MappedSuperclass` waren zunächst
  gesperrt, weil Hibernate den Member ohne explizite XML-Zugriffsart nicht
  fand. Der Development-Pfad setzt jetzt `FIELD` beziehungsweise `PROPERTY`
  und erlaubt eine solche Basisklasse für genau ein Entity-Mapping.
- Ein weiterer PostgreSQL-Test sortiert und paginiert per HQL und Criteria
  nach `p.title`. Dieselbe erste Seite enthält in `de` andere Pages als in
  `en`; die englische zweite Seite und ein HQL-Prädikat berücksichtigen den
  Fallback eines Felds ohne englischen Row. Wiederholte cachebare HQL-Abfragen
  treffen getrennte Locale-Einträge der testinternen Query-Region. Nach einer
  englischen Titeländerung wird die gecachte erste Seite neu berechnet; die
  deutsche Reihenfolge bleibt unverändert. Dafür ist der testinterne Flush-
  Listener auch in diesem Query-Versuch installiert.
- Der generische testinterne Map-Listener weist nun gleichzeitige Änderungen
  desselben Properties durch Domain-Feld und verwalteten Translation-Row vor
  dem Flush zurück. Der PostgreSQL-Test prüft Titelkonflikt samt Rollback und
  erlaubt daneben eine Domain-Titeländerung mit Editor-Content-Änderung.
  Diesen Konflikt innerhalb einer Session ergänzt nun eine separate Row-
  Version gegen veraltete Writes zwischen Sessions.
- Ein isolierter HBM-XML-Test belegt Versionierung und stale-write-Erkennung
  für eine klassenlose Map-Entity; HBM ist in Hibernate 7.4 veraltet. Im
  annotationbasierten Hauptversuch fügt stattdessen ein später testinterner
  Metadatenbeitrag `row_version` zur Map-Entity aus modernem XML hinzu. Die
  Domain-Entity bleibt unverändert. Ein `PostLoadEventListener` lädt den
  exakten Locale-Row beim Page-Laden, damit seine ursprüngliche Version vor
  dem Flush feststeht. Der PostgreSQL-Test prüft den stale Commit zweier
  Sessions derselben Locale und unabhängige parallele Commits für `de`/`en`.
  Nach `refresh()` lädt der Listener den evicteten Row sofort erneut; ein
  weiterer Konkurrenztest bestätigt den Versionsschutz auch danach. Diese
  Listener-Lösung erzeugt einen zusätzlichen Select je geladener Page und
  bleibt testintern und versionsgebunden.
- Ein isolierter PostgreSQL-Test mit testinternem In-Memory-`RegionFactory`
  belegt zwei Cache-Lecks des Formel-/`StatementInspector`-Ansatzes: Der
  Query-Cache gibt nach `de` auch für `en` den deutschen Skalarwert zurück;
  der Second-Level-Cache einer lokalisierten Parent-Entity gibt nach einem
  deutschen Load auch in einer englischen Session den deutschen Entity-State
  zurück. Ohne Query-Cache beziehungsweise nach Entity-Eviction liest `en`
  korrekt. `QueryKey` verwendet den SQL-String vor dem Inspector; der normale
  Entity-Cache-Key enthält keine Content-Locale. Der testinterne späte
  Metadatenbeitrag lehnt daher normale Query-Cache-Provider und gecachte
  lokalisierte Parents ab. Ein zusätzlicher testinterner
  `LocaleAwareRegionFactory`-Decorator delegiert Domain- und Timestamp-
  Regionen an den Provider und speichert Query-Resultate unter
  `(contentLocale, tenantId, QueryKey)`, wobei eine Session die Locale nur einmal binden
  darf. Die interne Bindung liegt nun in `i18n-hibernate` und speichert einen
  serialisierbaren, unveränderlichen Marker in einer Session-Property statt in
  einer globalen `WeakHashMap`. Auch die testinternen Flush-Listener verwenden
  diesen einen Wert. `Session.clear()` behält ihn; ein späterer Locale-Wechsel
  bleibt abgewiesen. Die Bindung prüft zudem, dass ihr `StatementInspector` dieselbe Locale
  in SQL einsetzt. Ohne Bindung bricht der Cache-Zugriff ab. PostgreSQL prüft getrennte
  skalare und Entity-Resultate für `de`/`en`, den `fr`-Fallback und
  Invalidierung nach Änderung des englischen Rows. Mit genau dieser Test-
  Region lässt der Metadatenbeitrag den Query-Cache zu; der Parent-Entity-
  Cache bleibt gesperrt. Ein gewöhnliches Entity bleibt im Second-Level-Cache
  nutzbar. Zwei Tenant-IDs teilen keinen Query-Region-Eintrag. Ein zusätzlicher
  PostgreSQL-Test prüft bei einem String-`@TenantId`-Parent die generierte
  Translation-Entity: XML-`<tenant-id>` filtert `find()` und HQL und wird beim
  Persistieren gesetzt; Formeln berücksichtigen dieselbe Tenant-Spalte. Ein
  Unique Key auf `(Parent-ID, Tenant-ID)` und ein zweispaltiger FK verwerfen
  Translation-Rows mit fremdem Parent-Tenant (`23503`). Cachebare Titelabfragen
  beider Tenants bleiben bei wiederholter Ausführung getrennt (zwei Puts,
  zwei Hits). Der Decorator ist bisher nur mit dem
  In-Memory-Provider geprüft. Die Speicherung beliebiger eigener Session-
  Properties ist für die gepinnte Hibernate-Implementierung getestet, aber
  keine eigens dokumentierte Locale-SPI. Nur die beiden Negativtests setzen
  `hibernate.i18n.experimental.allow_unsafe_cache_probe=true`. Der
  produktive Guard bleibt unverändert aktiv.
- Ein Parent-Delete mit FK-Cascade entfernte zwar die Datenbank-Rows, ließ
  aber bereits verwaltete Map-Rows nach `flush()` im selben Persistence Context
  sichtbar. Ein testinterner `PreDeleteEventListener` evictet beim Flush alle
  geladenen Locale-Rows des gelöschten Parents. Die PostgreSQL-Tests prüfen
  den Tenant-Row, zwei Locale-Rows einschließlich eines zuvor geänderten Rows,
  einen zweiten Flush, die Datenbank-Cascade und die Query-Cache-Invalidierung.
  Vor dem Flush und bei Bulk-Deletes ist dieser Pfad nicht abgesichert.
  `PostDeleteEventListener` löste in Hibernate 7.4.10.Final extern implementiert
  einen `IllegalAccessError` aus, weil sein Superinterface paketprivat ist.
- Ein eigener PostgreSQL-Test führt `delete from LocalizedPage` als HQL-Bulk-
  Mutation aus. Der FK löscht die Translation-Rows und die gecachte Page-Abfrage
  wird ungültig. Die bereits geladenen Page- und Map-Objekte bleiben jedoch
  im selben Persistence Context verwaltet; `find()` liefert zunächst die
  alten Rows. Nach einem bewussten `Session.clear()` liefern `find()`-Aufrufe
  `null`. Bulk-DML muss deshalb als separater Vertrag behandelt werden;
  der `PreDeleteEventListener` kann es nicht transparent reparieren.
- Der testinterne Map-Listener behandelt nun einen begrenzten
  `Session.refresh(page)`-Pfad: Nach Hibernates Refresh entfernt er einen
  unveränderten, schon verwalteten Translation-Row aus dem Persistence Context
  und lädt dessen aktuellen Stand samt Version neu; dann setzt er seinen
  Feldvergleichs-Snapshot neu. Der PostgreSQL-Test prüft,
  dass ein reiner Flush keinen Update auslöst und ein folgendes Titel-Update
  frisch geladenen Content nicht überschreibt. Ist der verwaltete Translation-
  Row zuvor ungespeichert geändert worden, bricht der Listener mit Exception
  ab; der Test prüft den Rollback. Das verwendet interne Hibernate-SPIs und
  beweist weder alle Refresh-Varianten noch Produktionsreife.
- Ein Merge-Test zeigte zunächst den Locale-Datenfehler: Eine detached `de`-
  Page wurde in einer `en`-Session gemergt und ihr deutscher Titel in den
  englischen Translation-Row geschrieben. Eine Herkunftsprüfung genügte nicht:
  Ein detached `fr`-Objekt kopierte nach einer Änderung des englischen Fallback-
  Rows den alten englischen Content in den französischen Row. Der testintern
  vorangestellte `MergeEventListener` weist daher **alle nicht verwalteten
  lokalisierten Entities** vor dem Kopieren ab, auch transiente neue.
  Ein Merge bereits verwalteter Instanzen ist
  erlaubt. Anwendungscode muss für detached Änderungen die Page in einer
  neuen Session mit Ziel-Locale laden und die gewünschten Felder dort setzen.
  Diese konservative Einschränkung verhindert die beiden im Test belegten
  Datenfehler; allgemeine Merge-Unterstützung ist offen.
- Alle Implementierungs- und Testklassen sind Scala. Nur `@Localized` und
  `@Translation` bleiben Java-Annotationen.
- Git ist auf `master` initialisiert. Es gibt bislang keinen Commit und keinen
  Remote. Die Projektdateien sind untracked; diese Arbeit erhalten.

Nach der Session-Bindungs-Extraktion, der Absicherung von `clear()`,
`evict()` und `replicate()`, der Korrektur des Same-Session-Row-Lookups, dem
Auto-Flush-Test für eine neue Page, der Cache-Policy-Extraktion und den
Getter-basierten Lese-, Schreib- und Session-Lifecycle-Versuchen lief
`sbt --server check` unter Windows mit JDK 25 und embedded PostgreSQL 14.22
erneut erfolgreich: alle 54 Tests bestanden (35 im Testmodul, 19 im
Development-Modul). Neue Development-Tests prüfen den Second-Level-Cache
mit einem speichernden Provider, die Deaktivierung des Caches für eine
lokalisierte Entity-Hierarchie, Hibernate-`update` auf befüllten Stack-
Tabellen und die DDL-Manager-Integrationsgrenze.
Darunter ist ein Guard-Test für `@Translation` am Getter ohne `@Localized`.
Der Guard bleibt aktiv; die Versuche liegen
bis auf die inaktiven Mapping-/Metadaten-Bausteine, die Cache-Policy, die
interne Locale-Bindung und die Row-Suche im nicht veröffentlichten Testmodul.
`i18nHibernate/packageBin` wurde ebenfalls gebaut: Im JAR sind Generator,
Finalizer, Cache-Policy, Locale-Bindung und Row-Suche enthalten, aber als Hibernate-Service
nur der produktive Guard registriert;
der testinterne `MetadataSourcesContributor` ist nicht enthalten.
Nach dem vollständigen Check wurde `scripts/dev-smoke.ps1` erneut ausgeführt;
alle drei Module einschließlich der typisierten Editor-API liegen als
`0.1.0-SNAPSHOT` im lokalen Ivy-Repository. Der eigenständige
`examples/development-consumer` lief mit eingebettetem PostgreSQL erfolgreich
und meldete `development-smoke: de=Hallo, en=Hello, editor=Hello`.

## Technische Erkenntnis und nächster Schritt

`AdditionalMappingContributor` läuft nach dem normalen Entity-Binding. Ein
`MetadataSourcesContributor` läuft früher und kann über `MetadataSources`
ausdrücklich registrierte Entity-Klassen und -Namen sehen sowie XML-Bindings
beitragen. Ein testinterner Contributor erzeugt daraus bereits Formeln,
Query-Space und dynamische Translation-Entity; ein später Contributor
ergänzt FK und Version-Property. Der produktive Guard wird nur im isolierten Bootstrap-Test
ausgefiltert. Ein
frühes XML-`<transient>` entfernt das Feld aus Parent-Mapping **und** Entity-
State; dieser Weg scheitert an Laden und Dirty Checking. Das neue
XML-`<formula>` hält es dagegen im Entity-State und ermöglicht Locale-Laden,
Converter und HQL-Filter. Die Formel ist read-only, `persister.findDirty()`
ignoriert alleinige Änderungen daran. Ein Versuchs-Listener kann einen
separat gemappten Translation-Row vor dem normalen Flush aktualisieren, sodass
Hibernate dessen Dirty Checking und ActionQueue nutzt. Dieser Row kann eine
Scala-Entity oder eine klassenlose Map sein; `Session.find(entityName, idMap)`
lädt letztere anhand ihres zusammengesetzten Schlüssels. Ein XML-`<synchronize>`
ist für HQL-Auto-Flush nötig, ohne das Domain-Modell zusätzlich zu annotieren.
Ein frisch persistierter Map-Row war nach `flush()` über `find(entityName, idMap)`
in derselben Session nicht auffindbar, obwohl er verwaltet und in der Datenbank
vorhanden war. `Session.getIdentifier(row)` lieferte dabei dieselbe mutable Map.
Der inaktive interne Hibernate-Baustein sucht deshalb zuerst unter den
verwalteten Translation-Rows des Persistence Contexts und nutzt `find()` erst
danach; der testinterne Synchronizer ruft ihn auf.
Ein PostgreSQL-Test ändert dasselbe übersetzte Feld zweimal in einer Session
und prüft den zweiten Datenbankwert. Ein weiterer Auto-Flush-Pfad legt eine Page
mit übersetztem Titel an und findet sie per HQL nacheinander unter zwei Titeln
in derselben Transaktion.
Die Cache-Schranken liegen jetzt in einer inaktiven internen Policy des
Hibernate-Moduls. Nur der testinterne Contributor erkennt den experimentellen
localebewussten Query-Region-Decorator und besitzt den Schalter für absichtlich
unsichere Negativtests; beides ist nicht als Produktions-Service registriert.
Die Test-Listener vermeiden bereits, unveränderte Parent-Werte über direkte
Editor-Änderungen am Translation-Row zu schreiben. Der annotierte Pfad deckt
auch einen `refresh()`-Fall mit vorher verwalteter Map-Zeile ab und verweigert
detached Merge sowie das veraltete `replicate()`. Bei `Session.clear()` und
`Session.evict()` werden die testinternen Feld-Snapshots entfernt. Ein
PostgreSQL-Test lädt dieselbe Objektinstanz danach über `Session.load(object, id)`
neu, nachdem der aktive Translation-Row entfernt wurde; der Fallback-Wert wird
nicht als expliziter Row zurückgeschrieben. Query- und Parent-Entity-Cache
sind im unveränderten
Formel-/Inspector-Ansatz nachweislich localeunsicher. Eine testinterne
Query-Region behebt die Kollision im Query-Cache; der Parent-Cache bleibt
gesperrt. Reentranz der Session-
Aufrufe im Flush-Callback, weitere Missing-/Fallback-Fälle,
weitere Admin-Edits und generalisierte Tenant-
Mappings sind noch offen. Envers' Contributor/Integrator-Aufteilung
löst diese Zustands- und Query-Anforderungen nicht automatisch.

Der annotationbasierte Test-Bootstrap ist mit dem testinternen Schreib-Listener
verbunden; der produktive Guard bleibt aktiv. Prüfe als Nächstes die
Reentranz und weitere Session-Pfade und generalisiere danach
Identifier, weitere Property-Access- und Naming-Varianten, `@Column` auf
übersetzten Feldern, Converter und Entity-
Entdeckung. Der FK entsteht bisher über `Table.createForeignKey`; ein direktes
`<many-to-one>` in der Map-Entity scheiterte in Hibernate 7.4.10.Final bei
`ToOneFkSecondPass` mit NPE. Außerdem die Ordnung bei Flush, AutoFlush und
weiteren Session-Pfaden testen; die localegetrennte Query-Region für reale
Cache-Provider, weitere Tenant-Mappings und Abfrageformen prüfen. Falls diese
Integration nicht gelingt,
den eigenen
`PersisterClassResolver`/`EntityPersister` oder eine gezielte Hibernate-Core-
Erweiterung unter 7.4.10 untersuchen. Erst nach solchen Nachweisen den Guard
entfernen oder die öffentliche API erweitern.

Wenn dieser Weg keine tragfähige versionsgebundene Implementierung ergibt,
beschreibe präzise die fehlende SPI und die nächste Architektur mit normaler
Entity-Typform und explizitem Repository-/Translation-Persistenzvertrag.
Behaupte nicht, dass dieser Vertrag automatisch Hibernate Dirty Checking für
ungemappte Felder bietet. Die noch offenen Punkte sind im Architekturbericht
aufgeführt. Lass den Guard aktiv, solange die Invarianten nicht geprüft sind.

## Befehle

```powershell
sbt --server "i18nTests/testFull"
sbt --server check
.\scripts\set-version.ps1 -Check
```

`check` führt `clean`, `testFull` und `i18nHibernate/verifyRuntimeJar` aus.
Die Artefaktprüfung ist nicht gecacht und kontrolliert die ServiceLoader-
Registrierung des gepackten JAR. embedded PostgreSQL benötigt keinen
externen Server; alternativ kann `HIBERNATE_I18N_TEST_POSTGRES` eine Testserver-
JDBC-URL enthalten. `exportJars := false` im Haupt- und Meta-Build verhindert
unter Windows Konflikte mit geöffneten JAR-Dateien des sbt-Servers. In einer
Sandbox kann für sbt Zugriff auf den lokalen Benutzer-Cache erforderlich sein.
