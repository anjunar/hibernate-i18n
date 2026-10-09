# Hibernate I18n: Architekturprüfung

> Historischer Bericht vor Version 1.0.0. Der aktuelle öffentliche Runtime-Vertrag steht in [runtime.md](runtime.md).

Stand: 3. Oktober 2026. Geprüft wurden die im Projekt gepinnten JARs und Quellen
von Hibernate ORM und Envers **7.4.10.Final**. Dieser Bericht unterscheidet das
gewünschte Verhalten von den Fähigkeiten, die tatsächlich nachgewiesen wurden.

## Verbindliches Ziel

Die Klarstellung vom 3. Oktober 2026 ersetzt die früheren Vorschläge
`page.localized(locale)`, `page.translation(locale)` und Property-Wrapper als
normale Domain-API. Die Entity behält ihre ursprünglichen Java-Typen:

```java
@Entity
@Localized
class Page {
    @Id UUID id;
    @Translation String title;
    @Translation Markdown content;
}
```

`page.getTitle()` und `page.getContent()` sollen den beim Laden gewählten
Content-Locale-Wert liefern. **Eine Session hat genau eine unveränderliche aktive
Content-Locale.** Bereits verwaltete `Page#42`-Instanzen wechseln ihren Inhalt
nicht, wenn anderswo eine andere Locale verwendet wird. Für eine andere Locale
wird normalerweise eine neue Session geöffnet. Eine explizite Translation-API
für Editoren ist nachgeordnet und ersetzt die normalen Getter nicht.

Die Übersetzung bleibt relational eigenständig: `(page_id, locale)` identifiziert
einen Row, während `Page#42` im Persistence Context eine einzige Entity bleibt.
Das Modul soll weder bei `Page` noch bei `Markdown` einen Wrapper-Typ verlangen.

## Hibernate-Bootstrap und Envers

`MetadataBuildingProcess.complete()` verarbeitet erst die normalen Entity-
Hierarchien und danach `processAdditionalMappingContributions()`. Ein
`AdditionalMappingContributor` kann per ServiceLoader Entity-Klassen,
ClassDetails, XML-Bindings, Tabellen und Sequenzen beitragen. Der Hook ist
`@Incubating`. Er kann eine eigene Translation-Entity ins Boot-Metamodell
bringen, kommt aber nach dem ersten Binding gewöhnlicher `Page`-Properties.
Quellen: `MetadataBuildingProcess.java`, `AdditionalMappingContributor.java`
und `AdditionalMappingContributions.java` aus dem gepinnten Source-JAR sowie
die [Contributor-](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/boot/spi/AdditionalMappingContributor.html)
und [Contributions-Dokumentation](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/boot/spi/AdditionalMappingContributions.html).

`MetadataSourcesContributor` wird in `MetadataBuilderImpl` früher aufgerufen.
Sein Argument `MetadataSources` bietet `getAnnotatedClasses()`,
`getAnnotatedClassNames()` und `addInputStream(...)`. Für ausdrücklich
registrierte Klassen ist das ein konkreter Ansatz, ein aus `@Translation`
erzeugtes Formel-/Map-Overlay noch vor dem normalen Binding einzuspeisen.
Ein **testinterner, per ServiceLoader registrierter**
`MetadataSourcesContributor` liest diese Klassen beziehungsweise Klassennamen,
erkennt `@Localized` und `@Translation` und ruft den internen
`TranslationMappingXml`-Generator aus `i18n-hibernate` auf. Dieser erzeugt das
XML-Overlay mit `<formula>`, `<synchronize>` und einer klassenlosen Map-Entity.
Er ist im veröffentlichten Modul nicht als Contributor registriert. Ein ebenfalls
testinterner `AdditionalMappingContributor` ruft den internen
`TranslationMetadataFinalizer` aus `i18n-hibernate` auf. Dieser ergänzt den
FK mit Delete-Cascade und die Row-Version nach dem Binding. Der PostgreSQL-Test startet allein mit dem Namen einer
annotierten Scala-Entity und belegt die erzeugte Tabelle ohne übersetzte
Parent-Spalten, den Composite Key, FK/Cascade sowie Locale-Laden und HQL-
Filter für `String` und converterbasiertes `Markdown`.
Ein weiterer PostgreSQL-Test variiert die physische Naming-Strategie. Der frühe
XML-Beitrag kennt nur logische Namen aus Annotationen; SQL-Formeln müssen aber
physische Namen verwenden, wie [Hibernates Naming-SPI](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/boot/model/naming/PhysicalNamingStrategy.html)
beschreibt. Der späte Beitrag liest deshalb Tabelle, ID-, Locale-, Tenant-
und Wertspalten aus den gebundenen Metadaten, setzt die Formel neu und
registriert den physischen Translation-Query-Space. Snake Case und eine eigene
Tabellen-/Spaltenpräfix-Strategie bestehen Laden, HQL-Filter und Query-Cache-
Invalidierung. Auch eine quotierende Strategie mit großgeschriebenen Tabellen-
und Spaltennamen besteht diese PostgreSQL-Pfade: Der späte Beitrag rendert
die gebundenen Namen mit Hibernates Dialekt-Quoting. Die spät hinzugefügte
`row_version`-Spalte durchläuft ebenfalls die PhysicalNamingStrategy;
Präfixe und quotierte Uppercase-Namen sind im PostgreSQL-Test geprüft.
Das Development-Profil akzeptiert außerdem Stack-Tabellennamen mit `#` und
quotiert die daraus abgeleitete Translation-Tabelle. Ein PostgreSQL-Test mit
`Offerings#Offering` und `Schedule#Event` prüft dies unter der im Stack
verwendeten Camel-Case-Naming-Strategie einschließlich HQL-Auto-Flush.
Der dynamische Translation-Entity-Name liegt jetzt in einem internen Namespace
mit dem vollständigen Parent-Klassennamen; bestehende `*Translation`-Entities
bleiben daneben gemappt. Ihre Daten werden dadurch nicht migriert.
Weitere Dialekte und
Identifier-Formen sind nicht geprüft. Ein `@Column` direkt am
`@Translation`-Attribut verdrängt im aktuellen XML-Overlay die Formel; diese
Kombination wird früh abgewiesen. Ein optionales
`@Translation(column = "displayTitle")` steuert dagegen nur den Namen der
Translation-Spalte. Feld- und Getter-Zugriff wurden mit Snake-Case-Naming,
Parent ohne diese Spalte, Laden und HQL geprüft. Doppelte oder interne
Schlüsselspalten-Namen scheitern beim Bootstrap. Auch erst nach der physischen
Benennung entstehende Kollisionen mit Schlüssel-, Versions- oder Wertspalten
werden abgewiesen. Das ist eine Grenze des Testprototyps,
keine gewünschte Einschränkung der späteren öffentlichen API.
Ein früherer Versuch mit `@Id` und `@Translation` in einer
`@MappedSuperclass` scheiterte in Hibernate 7.4.10.Final mit
`Could not locate attribute member - title`. Der entscheidende Auslöser war
der implizite XML-Zugriff: Mit explizitem `access="FIELD"` beziehungsweise
`access="PROPERTY"` bindet Hibernate das `<basic>` auf der deklarierenden
MappedSuperclass als Formel. Der Development-Test belegt mit PostgreSQL
geerbte UUID-ID, Version, Tenant, ein geerbtes und ein direktes übersetztes
Feld, Laden, Fallback und Schreiben. Ein Metadaten-Test prüft den geerbten
JavaBean-Getter. Die Translation-Spalte fehlt in der Parent-Tabelle.
Eine übersetzte Property, die in einer `@Entity` der `SINGLE_TABLE`-Hierarchie
deklariert ist, funktioniert auch für deren unannotierte Entity-Unterklasse.
Weil das XML-Overlay einer MappedSuperclass für alle Entity-Abkömmlinge gilt,
weist der Bootstrap mehrere Entity-Mappings mit demselben geerbten
`@Translation`-Member zurück. Ein unlokalisiertes Entity-Mapping mit
`@Translation` wird ebenfalls zurückgewiesen. Der neue Pfad bleibt ein
versionsgebundener Development-Prototyp; gemeinsame Basisklassen und weitere
Vererbungskonstellationen sind damit nicht allgemein freigegeben.
Ein separater PostgreSQL-Test sortiert und paginiert per HQL und Criteria
nach dem lokalisierten `title`. Die erste Seite enthält in `de` andere
Entities als in `en`; die englische zweite Seite sowie ein Prädikat zeigen
den Fallback bei einem fehlenden Locale-Row. Die cachebaren HQL-Seiten
werden nach Content-Locale getrennt gespeichert und erneut getroffen. Nach
einer Titeländerung über den testinternen Flush-Listener wird die sortierte
Seite neu berechnet, während die andere Locale ihre Reihenfolge behält.
Der generische testinterne Map-Listener verbindet dies mit HQL-Auto-Flush,
Änderungen beider Felder, Insert, direktem Editor-Update und Rollback. Nach
dem Editor-Update behält die verwaltete Page ihren bisherigen Feldwert; eine
neue Session lädt den neuen.
Ein weiterer PostgreSQL-Test ändert in derselben Session das Domain-Feld
`page.title` und den Titel des verwalteten Translation-Rows unterschiedlich.
Ohne Prüfung hätte der Vor-Flush-Listener den Editorwert still überschrieben.
Der testinterne Map-Listener vergleicht nun den Row mit Hibernates geladenem
Snapshot und wirft bei Änderungen am **selben Property** eine Exception;
die Transaktion rollt zurück. Eine Domain-Titeländerung und eine direkte
Editor-Content-Änderung bleiben dagegen kombinierbar. Diese Prüfung erkennt
Konflikte innerhalb eines Persistence Context; parallele Transaktionen
benötigen zusätzlich die unten beschriebene Row-Version.
Die Fallback-Formel priorisiert exakte Session-Locale, Sprachanteil,
`fallbackLocale` und `defaultLocale` **je Property**. Der Session-Inspector
setzt dafür zusätzlich einen Sprach-Marker. Der PostgreSQL-Test prüft
`de-DE → de`, `fr → en` und bei fehlendem `en` den Default `de`; bloßes
Lesen erzeugt keinen neuen Locale-Row. Der Listener schreibt nur geänderte
Properties: Ein in `fr` geänderter Titel legt `content = null` im neuen
`fr`-Row an. Der englische Content bleibt Fallback und seine spätere Änderung
wird in einer neuen `fr`-Session sichtbar. `null` hat nun die festgelegte
Bedeutung „Locale-Override entfernen“: Bei fehlendem exaktem Row erzeugt der
Listener für eine reine Null-Änderung keinen leeren Row. Ist nach dem Leeren
des Feldes auch das letzte andere Translation-Feld null, löscht er den
ganzen Row. Wenn ein anderes Feld noch einen Wert trägt, bleibt der Row mit
diesem Feld bestehen. Die Tests prüfen alle drei Fälle und dass der Fallback
nach `refresh()` oder in einer neuen Session wieder erscheint. Direkt nach
der Setter-Zuweisung enthält das verwaltete Scala-Feld weiterhin null; der
Formelwert wird erst durch erneutes Laden aktualisiert. Eine konfigurierbare
Strategie und weitere BCP-47-Fälle sind noch offen. Die Formel führt pro übersetzter Property mehrere skalare
Subqueries aus; Last- und Indexverhalten wurden noch nicht gemessen.

Ein weiterer PostgreSQL-Versuch deckt `Session.refresh(page)` ab. Nach einem
vorherigen Flush werden Titel und Content per SQL im selben Transaktionskontext
geändert. `refresh()` lädt die neuen Formelwerte in die bereits verwaltete
`Page`. Ohne zusätzlichen Listener verglich der Vor-Flush-Synchronisierer noch
mit seinem alten Snapshot und erzeugte ein unnötiges Update. Ein bloßes
Zurücksetzen dieses Snapshots genügte ebenfalls nicht: Die bereits verwaltete
Map-Translation enthielt noch den alten Content und schrieb ihn beim nächsten
Titel-Update zurück. Der testinterne `RefreshEventListener` wird deshalb nach
Hibernates Standard-Listener ausgeführt. Er entfernt einen unveränderten
Translation-Row aus dem Persistence Context, lädt dessen aktuellen Stand
erneut und setzt anschließend den Vergleichs-Snapshot auf die frisch geladenen
Domain-Werte. Der Test prüft, dass ein reiner Flush nach
`refresh()` keinen Update zählt und ein späteres Titel-Update den neu geladenen
Content nicht überschreibt. Ein weiterer Test ändert den verwalteten
Translation-Row vor `refresh()` absichtlich, erwartet eine Exception und prüft
den Rollback; solche Änderungen werden nicht still durch `evict()` verworfen.
Diese Behandlung verwendet `PersistenceContext`, `EntityPersister.findDirty()`
und Listener-Reihenfolge aus Hibernate 7.4.10.Final. Sie ist ein begrenzter
Lifecycle-Beleg im Test, keine stabile öffentliche SPI oder produktive Zusage.
[Die Session-API](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html)
beschreibt `refresh()` als erneutes Lesen des persistenten Zustands.

Der interne Map-Translation-Row besitzt im aktuellen Test nun `row_version`.
Ein erster HBM-XML-Versuch zeigte, dass Hibernate eine klassenlose Map-Entity
versioniert und bei zwei konkurrierenden Updates des gleichen Rows eine
`StaleObjectStateException` wirft. Das ältere HBM-Format wird von Hibernate
7.4 als veraltet gemeldet. Der annotationbasierte Hauptversuch bleibt beim
modernen XML-Overlay: Ein später testinterner Metadatenbeitrag ergänzt dort
die Version-Property und Spalte **nur** an der Map-Entity. Die normale `Page`
hat weiterhin weder Versionsfeld noch Translation-Wrapper. Um die Version
rechtzeitig festzuhalten, lädt ein `PostLoadEventListener` beim Laden der Page
den Row ihrer **exakten** Session-Locale in denselben Persistence Context.
Ohne diesen Schritt lädt der Vor-Flush-Listener den Row erst nach dem ersten
Commit der anderen Session und übersieht den Konflikt trotz Versionierung.
Nach `refresh()` lädt der Refresh-Listener den Row erneut; ohne dieses Reload
ging die Versionsgarantie für einen anschließenden Write wieder verloren.
Der PostgreSQL-Test belegt beides: Ein veralteter Schreibversuch derselben
Locale scheitert, auch nach `refresh()`; parallele Updates von `de` und `en`
gelingen, weil sie verschiedene Row-Identitäten und Versionen besitzen.
Die zusätzlichen `PostLoad`-Selects können zu N+1-Abfragen führen und wurden
noch nicht auf Last getestet. Die programmatische Version-Property und die
Session-Aufrufe im Listener verwenden versionsgebundene Hibernate-Metadaten
bzw. Lifecycle-Hooks; Reentranz und Missing-Row-Sicherheit bleiben ungeprüft.

Ein eigener PostgreSQL-Cache-Test belegt zwei Locale-Lecks des aktuellen
Formel-/`StatementInspector`-Versuchs. Mit eingeschaltetem Query-Cache ergibt
eine gecachte `de`-Abfrage `Hallo`; dieselbe Abfrage in einer neuen `en`-Session
liefert ebenfalls `Hallo`, obwohl eine ungecachte `en`-Abfrage `Hello` liefert.
Hibernate 7.4.10.Final baut `QueryKey` aus `jdbcSelect.getSqlString()` und den
Parametern **vor** der SQL-Änderung im `StatementInspector`; die aktive Locale
ist weder SQL-Parameter noch Filter-Key. Der Cache-Key ist deshalb für beide
Sessions gleich. Ein zweiter Test aktiviert den Second-Level-Cache auf der
Parent-Entity: `find()` in `en` übernimmt den in `de` gecachten Formel-State.
Eine inaktive interne Cache-Policy verweigert beim Metadatenaufbau Query-Caching
ohne vom aufrufenden Contributor bestätigte Locale-Trennung und
Second-Level-Caching der lokalisierten
Parent-Entity. Der Test-Contributor liefert den Nachweis nur für seinen
experimentellen Query-Region-Decorator; sein Bypass für Negativtests bleibt
vollständig im Testmodul.
Nach Eviction liest eine neue `en`-Session den richtigen Wert. Der übliche
Entity-Cache-Key enthält Entity-Name, ID und gegebenenfalls Tenant, aber keine
Content-Locale. Ein zweiter, ebenfalls **testinterner**
[`RegionFactory`-Decorator](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/cache/spi/RegionFactory.html)
delegiert Domain- und Timestamp-Regionen an den konfigurierten Provider und
legt Query-Resultate unter `(contentLocale, tenantId, QueryKey)` ab. Die Locale
liegt nun als unveränderlicher Marker in einer Property der jeweiligen Session;
die globale `WeakHashMap` des Prototyps entfällt. Beim Binden wird auch geprüft, ob der
Session-`StatementInspector` dieselbe Locale in die SQL-Formel einsetzt;
eine abweichende oder spätere andere Locale wird abgewiesen. Ohne Bindung
bricht ein Cache-Zugriff ab. `Session.clear()` entfernt den Marker nicht.
Der testinterne Feld-Snapshot wird dagegen bei `clear()` und `evict()` entfernt:
`Session.load(object, id)` kann dieselbe Instanz wieder mit Datenbankzustand
füllen. Ein PostgreSQL-Test löscht dazwischen den aktiven Translation-Row und
prüft, dass der danach geladene Fallback nicht erneut als aktiver Row entsteht.
Das veraltete `Session.replicate()` wird für lokalisierte Entities vor der
Hibernate-Verarbeitung abgewiesen, da es detached State übernehmen kann.
Ein weiterer Testpfad zeigte bei der klassenlosen Map-Entity eine Lücke im
Same-Session-Lookup: Nach `persist()` und `flush()` war der Row verwaltet und
in PostgreSQL vorhanden, aber `Session.find(entityName, idMap)` lieferte für
den zusammengesetzten Schlüssel `null`. `Session.getIdentifier(row)` gab die
mutable Row-Map selbst zurück. Ein inaktiver interner Hibernate-Baustein
durchsucht daher zuerst die verwalteten Entries nach Entity-Name und den
Schlüsselfeldern. Eine
zweite Änderung desselben Feldes in derselben Session trifft dadurch den
bestehenden Row und wird als Update geprüft. Diese Abhängigkeit von internem
Persistence-Context-Zugriff ist weiterhin keine stabile öffentliche Runtime-SPI.
Ein weiterer PostgreSQL-Test erzeugt eine neue Page und findet sie bei zwei
aufeinanderfolgenden HQL-Abfragen unter dem jeweils aktuellen übersetzten
Titel. Damit sind Insert-Reihenfolge und Auto-Flush für diesen engen Pfad
geprüft.
[`Session.setProperty`](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html#setProperty(java.lang.String,java.lang.Object))
ist öffentlich, aber als Hint-API dokumentiert. Dass Hibernate 7.4.10.Final
einen eigenen serialisierbaren Wert darin speichert, ist am gepinnten
`SessionImpl` geprüft; das ist keine dedizierte, versionsunabhängige Locale-SPI.
Die Bindung prüft das Zurücklesen und bricht ab, falls eine Implementierung
den Wert nicht hält oder ersetzt. PostgreSQL-Tests bestätigen getrennte Treffer für `de`, `en` und
das auf `en` zurückfallende `fr`, sowohl für skalare als auch für Entity-
Abfragen. Nach einem Update des englischen Translation-Rows werden die
englischen und französischen Resultate neu gelesen; `de` bleibt korrekt. Ein
zweiter Testzugriff bestätigt, dass gewöhnliche, nicht lokalisierte Entities
ihren Second-Level-Cache weiterhin nutzen. Zwei Session-Tenant-IDs erhalten
getrennte Einträge bei demselben ursprünglichen `QueryKey`. Zusätzlich prüft ein
PostgreSQL-Testpfad für ein Parent mit String-`@TenantId` die relationale Grenze:
Das generierte Map-Entity trägt ein XML-`<tenant-id>`, das Hibernate beim Persistieren
setzt und bei `find()` sowie HQL filtert. Jede Formel vergleicht auch die Tenant-Spalte
mit dem Parent. Ein Unique Key auf `(Parent-ID, Tenant-ID)` und ein zweispaltiger FK
von der Translation-Tabelle verhindern Rows mit fremdem Parent-Tenant. Der Test
weist einen solchen Insert mit PostgreSQL-Fehler `23503` ab. Dieser Nachweis gilt
für die enge Testkonfiguration mit UUID-ID, Feldzugriff und String-Tenant-ID.
Dieselbe Integration fragt beide Tenants zweimal mit aktiviertem Query-Cache ab:
Beide erhalten ihren eigenen Titel, bei zwei Cache-Puts und zwei Cache-Hits.

Der Delete-Cascade-FK entfernt Translation-Rows aus der Datenbank, aber ein
bereits verwalteter Map-Row blieb nach `session.remove(page); session.flush()`
im selben Persistence Context erreichbar. Ein testinterner
`PreDeleteEventListener` entfernt beim Flush alle geladenen Locale-Rows
dieses Parents aus dem Session-Cache. Tests prüfen dies mit einem geladenen
Tenant-Row und mit zwei geladenen Locale-Rows, darunter einer vor der Löschung
geänderten Zeile. Ein zweiter Flush legt sie nicht erneut an. Nach Commit
bleiben die Rows gelöscht und die tenantgetrennten Query-Cache-Ergebnisse
werden aktualisiert. Vor dem Flush liegt die Row noch in der Datenbank;
Bulk-Deletes und andere Session-Pfade sind damit nicht abgedeckt. Ein
`PostDeleteEventListener` war in Hibernate 7.4.10.Final als externe
Implementierung nicht lauffähig: Das öffentliche Interface erweitert das
paketprivate `PostActionEventListener`, was beim Laden der Testklasse einen
`IllegalAccessError` auslöste.
Ein weiterer PostgreSQL-Test belegt den Bulk-Fall konkret: `delete from
LocalizedPage` entfernt per FK die Translation-Rows und invalidiert die
cachebare Page-Abfrage, aber eine zuvor geladene Page und ihre beiden Map-Rows
bleiben im ausführenden Persistence Context erreichbar. Erst `Session.clear()`
entfernt diese veralteten verwalteten Zustände; danach geben `find()`-Aufrufe
`null` zurück. Diese explizite Session-Grenze ist für Bulk-DML nötig. Der
`PreDeleteEventListener` sieht diese Operation nicht.

Der späte Metadatenbeitrag erlaubt in diesem engen Testpfad einen global
aktivierten Query-Cache nur mit genau diesem localebewussten Decorator.
Andernfalls lehnt er ihn ab. Gecachte lokalisierte Parent-Entities weist er
weiterhin ab; deren Second-Level-Key und gespeicherter Formel-State bleiben
localeunsicher. Nur die isolierten Negativtests setzen
`hibernate.i18n.experimental.allow_unsafe_cache_probe=true`. Der produktive
Mapping-Guard bleibt aktiv. Der Decorator ist gegen den testinternen In-Memory-
Provider geprüft, noch nicht gegen einen realen Cache-Provider. Er verlangt
eine Locale-Bindung für jede cachebare Session und verwendet Hibernate-Cache-
SPIs. Weitere Tenant-Mappings und Query-Arten, verteilte Provider
und Management-Operationen sind nicht geprüft. Für eine freigegebene Bibliothek
braucht es diese Nachweise oder eine verlässliche Deaktivierung lokalisierter
Query-Caches, plus eine klare Strategie für die Parent-Entity. Die
Map-Translation-Rows könnten wegen
`(pageId, locale)` getrennt gecacht werden; das ist noch nicht geprüft.

Der nächste Versuch mit `Session.merge()` fand einen weiteren konkreten
Locale-Bruch: Eine aus `de` geladene, danach abgehängte `Page` wurde in einer
`en`-Session gemergt. Hibernate kopierte auch das Formel-Property auf die neue
verwaltete Instanz; der Vor-Flush-Listener schrieb daraufhin den deutschen
Titel in den englischen Translation-Row. Der Test belegte diesen falschen
Update zunächst ohne Schutz. Ein jetzt testintern vorangestellter
`MergeEventListener` blockiert deshalb zunächst fremde Locale-Herkunft. Ein
zweiter Test zeigte aber, dass auch **dieselbe** Locale nicht genügt: Eine in
`fr` geladene Page hatte englischen Fallback-Content. Nachdem der englische
Row geändert wurde, schrieb ein Merge der detached französischen Page deren
alten, unveränderten Content in den französischen Row und kappte den Fallback.
Der Test belegte den falschen Wert `**English**` statt eines leeren französischen
Content-Felds. Der Listener weist jetzt jeden Merge einer **nicht verwalteten**
lokalisierten Entity vor Hibernates Kopiervorgang ab, also auch den Merge einer
transienten neuen Entity. Ein Merge der bereits in derselben Session verwalteten
Instanz bleibt möglich. Für detached Änderungen muss Anwendungscode die Entity
in einer neuen Session mit der gewünschten Locale laden und dort gezielt
ändern; neue Entities werden mit `persist()` angelegt. Das Domain-Modell
bekommt dadurch keinen Wrapper. Eine spätere echte
Merge-Unterstützung müsste Änderungen je übersetzter Property gegenüber einem
verlässlichen detached Snapshot bestimmen und Fallback-/Concurrency-Zustand
berücksichtigen; dafür ist hier kein sicherer allgemeiner SPI-Weg belegt.
[Die Hibernate-Session-API](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Session.html)
beschreibt `merge()` als Kopie des detached State auf eine verwaltete Instanz.

Nur für diesen isolierten Test wird der **produktive Guard** über einen eigenen
Bootstrap-ClassLoaderService aus der Service-Liste gefiltert. Alle normalen
Builds mit `@Translation` werden weiterhin vom Guard abgewiesen. Der
Generator unterstützt inzwischen Feld- oder JavaBean-Getter-Zugriff, genau ein
UUID-`@Id`, einfache Spaltenbezeichner und String-Datenbankwerte einschließlich
eines `AttributeConverter`. Ein PostgreSQL-Test belegt getterbasierte Lese- und
HQL-Pfade für `String` und konvertiertes `Markdown`, Locale-Fallback und fehlende
Translation-Spalten in der Parent-Tabelle. Der testinterne Map-Listener belegt
auch getterbasierte Inserts und Updates, HQL-Auto-Flush einer neuen Page und
das Entfernen einer leeren Locale-Zeile zugunsten des Fallbacks. Für Getter-
Entities zeigt ein PostgreSQL-Test außerdem das Flush-Verhalten: `COMMIT`
liefert vor Commit noch den alten HQL-Skalarwert und schreibt beim Commit;
`MANUAL` schreibt weder bei HQL noch beim Commit, aber bei explizitem
`flush()`; `ALWAYS` schreibt vor der nächsten HQL-Abfrage. Das sind Belege
für diese Session-Pfade, nicht für eine allgemeine Listener-Freigabe. Für Getter-
Entities sind auch Refresh nach externem Row-Update, Ablehnung eines Refresh
bei dirty Editor-Row, Snapshot-Reset bei `clear()`/`evict()` sowie die
Ablehnung von detached `merge` in gleicher und anderer Locale belegt. Der Test
weist `replicate()` ab, evicted verwaltete Translation-Rows beim Parent-Delete
und erkennt konkurrierende Writes derselben Locale über die Row-Version;
getrennte Locale-Rows können parallel geändert werden. Getterbasierte Skalar-
und Entity-Abfragen bleiben mit der testinternen localegetrennten Query-Region
bei Cache-Treffern und Row-Invalidierung korrekt; der Nachweis nutzt den
In-Memory-Provider. Der Parent-Entity-Cache bleibt gesperrt. Gemischter
Feld-/Getter-Zugriff wird beim Bootstrap abgewiesen; andere Identifier-, Naming-, Converter- und
Registrierungsformen sind offen.
Ob der frühe Hook alle über andere
Bootstrap-Pfade entdeckten Entities rechtzeitig sieht, ist nicht belegt.
[Die `MetadataSources`-API](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/boot/MetadataSources.html)
zeigt diese Zugriffspunkte. Die Aufrufreihenfolge wurde im gepinnten
`MetadataBuilderImpl.java` geprüft.
Ein kleiner Versuch mit erweitertem `orm.xml` hat bestätigt, dass ein XML-
`<transient>`-Overlay ein `String title` aus der Parent-Tabelle und
Parent-Metadata ausschließen kann. Der reproduzierbare
`TransientOverlayExperimentSuite` prüft außerdem mit PostgreSQL, dass ein
derart entferntes Feld beim erneuten Laden `null` ist und eine alleinige
Änderung daran nicht gespeichert wird. **Das Feld ist aus dem von Hibernate
verwalteten Entity-Zustand ausgeschlossen.** Der Versuch belegt, warum ein
Overlay die gewünschte Laufzeitsemantik allein nicht erreicht.
Quellen: `MetadataBuilderImpl.java` und `mapping-7.0.xsd` aus den gepinnten JARs.

Ein zweiter, ausführbarer Versuch nutzt stattdessen ein frühes XML-Overlay
`<basic name="title"><formula>…</formula></basic>`. Damit bleibt `title`
im normalen Entity-State und im Hibernate-Metamodell, während die Parent-Tabelle
keine `title`-Spalte erhält. Die Formel liest eine Zeile aus
`formula_page_translation` für die aktive Locale. Ein pro Session gesetzter
`SessionBuilder.statementInspector(UnaryOperator<String>)` ersetzt dafür einen
festen, validierten Locale-Marker im SQL. Der Test lädt dieselbe Parent-ID in
getrennten deutschen und englischen Sessions, filtert mit HQL nach `p.title`
und erhält bei `Markdown content` mit JPA-`AttributeConverter` wieder den
ursprünglichen Java-Typ. Die Locale ist im verwendeten Session-Builder fixiert.
Das ist ein konkreter **Lese-Pfad** ohne Property-Wrapper und ohne eigenen
`EntityPersister`, aber SQL-Text-Rewriting ist noch keine fertige allgemeine
Locale-Bindung. [SessionBuilder.statementInspector](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/SessionBuilder.html)
ist ein öffentlicher Hook; ob diese Markertechnik mit allen Dialekten und
Query-Formen belastbar ist, bleibt zu prüfen.

Im erweiterten Versuch ist `formula_page_translation` eine eigene Scala-
Hibernate-Entity mit `@EmbeddedId(pageId, locale)`, Parent-Fremdschlüssel und
`ON DELETE CASCADE`; Hibernate erzeugt Tabelle, PK und FK. Diese Klasse liegt
nur im Testmodul und steht stellvertretend für ein später intern erzeugtes
Mapping. Die Domain-Entity `FormulaPage` benötigt keine Translation-Association.
Alle Implementierungs- und Testklassen dieses Projekts sind Scala; nur die
öffentlich nutzbaren `@Localized`- und `@Translation`-Annotationen sind Java.

Ein weiterer Test ersetzt diese handgeschriebene Translation-Entity durch ein
**klassenloses Map-Mapping**. Der isolierte Versionierungsversuch nutzt HBM-XML;
das Formel-/Schreibexperiment und der annotationbasierte Pfad nutzen Hibernates
erweitertes `mapping.xml`. Die XML-Entity besitzt nur einen Namen, keine JVM-
Klasse; im modernen Format gibt `<target>` die Typen der Map-Werte an.
Hibernate baut daraus den zusammengesetzten Schlüssel `(page_id, locale)`,
Schema, Identifier-Laden und CRUD. Der Identifier ist ebenfalls eine Map mit
`pageId` und `locale`. Ein zweiter PostgreSQL-Test kombiniert dieses Mapping
mit den Formel-Properties der gewöhnlichen Scala-
`FormulaPage`. Er weist getrennte Locale-Werte, HQL-Auto-Flush, Änderungen an
`String` und `Markdown`, Insert, Rollback und Delete-Cascade nach, ohne eine
Translation-Klasse zu deklarieren. Für `Markdown` konvertiert der Parent-
Converter den Formelwert beim Lesen; der Test-Listener schreibt dessen
`source`-String in die interne Map. Das ist noch **keine** automatische
Ableitung aus `@Translation` in diesem Schreibtest und keine produktive
Flush-Strategie. Der separate Annotation-Bootstrap-Test verbindet dieselbe
testinterne Schreibstrategie inzwischen mit automatisch beigetragenem Mapping.

Der FK mit `ON DELETE CASCADE` wird in diesem Versuch vor dem Bau der
SessionFactory über `Table.createForeignKey` und `ForeignKey`-Metadata
beigetragen. Ein direkter `<many-to-one>`-Eintrag in der klassenlosen XML-
Entity scheiterte unter 7.4.10.Final während `ToOneFkSecondPass` mit einer
`NullPointerException`. Der Metadata-Eingriff ist ebenfalls versionsgebunden
und muss bei Upgrades geprüft werden. [Hibernates Dynamic-Model-
Dokumentation](https://docs.hibernate.org/orm/7.4/userguide/html_single/#dynamic-model)
beschreibt Map-Entities als unterstütztes Modell, belegt aber weder diese
FK-Erzeugung noch die Synchronisierung mit Domain-Feldern.

Das gepinnte Envers-JAR registriert getrennt
`AdditionalMappingContributorImpl` und `EnversIntegrator` über
`META-INF/services`. Sein Contributor initialisiert den EnversService und
trägt Audit-Mappings bei; sein Integrator registriert Insert-, Update-,
Delete- und Collection-Listener. Audit-Tabellen bilden historische Ereignisse
ab, während eine aktive Übersetzung zum veränderbaren Zustand eines geladenen
Domain-Objekts werden soll. Der Integrator läuft während des SessionFactory-
Aufbaus und kann keine bereits fehlende Property im Boot-Metamodell ersetzen.
[Hibernate warnt](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/integrator/spi/Integrator.html),
dass die SessionFactory zu diesem Zeitpunkt noch nicht vollständig initialisiert ist.

## Warum die einfachen SPIs nicht genügen

| Ansatz | Erreicht | Offener Bruch zum Ziel |
| --- | --- | --- |
| Frühes XML-`<transient>`-Overlay + dynamische Translation-Entity | Getrennte Tabelle und potenziell ein Row pro Locale | Das aus `Page` entfernte Feld wird nicht in dessen geladenen Zustand übernommen und nicht von dessen Dirty Checking überwacht. |
| Frühes XML-`<formula>`-Overlay | Normales Java-Feld bleibt im Hibernate-State; Lesen und HQL-Prädikate funktionieren im Versuch | Die Formel ist nicht updatefähig. Das normale `findDirty()` meldet eine alleinige Änderung des Formelwerts nicht. Locale-Bindung, Schreibaktionen und Cache brauchen weitere Integration. |
| Formel + gemappte Translation-Entity + vorangestellter Flush-Listener | Im Versuch übernimmt Hibernate Inserts, Updates, Converter, FK und Rollback des Translation-Rows | Der Listener ruft bei einem Flush `Session.find`/`persist` auf; Reentranz, alle Flush-Modi, Cache und Admin-Edits sind noch nicht als stabile Integrationsstrategie bewiesen. |
| Formel + klassenlose Map-Translation + vorangestellter Flush-Listener | Derselbe begrenzte Lese-/Schreibpfad funktioniert ohne Translation-Klasse; Hibernate verwaltet Map-Rows mit Composite Key | Mapping, FK und Query-Space werden im Test ausdrücklich angelegt; Listener-Reentranz, Converter-Generalisierung und Cache bleiben offen. |
| Annotationen + testinterne Bootstrap-Contributors + Map-Listener | Generiert für eine einfache Scala-Entity Formel, Query-Space, Map-Tabelle, Composite Key, FK und interne Row-Version; Laden, HQL sowie begrenzte Schreib- und Refresh-Pfade funktionieren; gleichzeitige Domain-/Editor-Edits am selben Property und stale Writes desselben Locale-Rows werden erkannt. Getterbasierte `String`- und konvertierte `Markdown`-Properties funktionieren beim Lesen und in HQL samt Fallback; der testinterne Listener belegt auch getterbasierte Inserts, Updates, Auto-Flush und Entfernen eines Overrides. Für ein String-`@TenantId`-Parent filtern Map-Entity und Formel nach Tenant; ein zweispaltiger FK wehrt fremde Parent-Tenants ab. Snake-Case-, Präfix- und quotiertes Uppercase-Naming funktionieren im PostgreSQL-Test einschließlich Query-Cache-Invalidierung. | Detached Merge wird wegen nachgewiesenem Locale- und Fallback-Datenverlust abgewiesen. Der unveränderte Query- und Parent-Entity-Cache teilt falsche Locale-Werte; ein testinterner RegionFactory-Decorator trennt Query-Resultate nach Locale und Tenant, der Parent-Cache bleibt gesperrt. Weitere Listener- und Session-Pfade für Getterzugriff bleiben offen. UUID-ID und String-Datenbankwerte bleiben Voraussetzung; gemischter Zugriff und `@Column` an `@Translation` werden abgewiesen. Weitere Dialekte, Identifier-Formen, Transaktionsfälle, Flush-Reentranz und Session-Pfade bleiben offen. Der produktive Guard bleibt aktiv. |
| `Integrator` mit Post-Load-/Update-Listenern | Kann nach Bootstrap auf Ereignisse reagieren | Für ein allein geändertes, ungemapptes `title` gibt es keinen regulären Parent-Update-Event; eine zweite Persistenzpipeline im Flush wäre nötig. |
| `Interceptor` / `CustomEntityDirtinessStrategy` | Kann den **gemappten** Entity-Zustand inspizieren oder verändern | Die State-/Property-Arrays enthalten ein als transient entferntes `title` nicht. Hibernate untersagt außerdem Session-Aufrufe aus einem Interceptor-Callback. |
| `@Formula` / `@JoinFormula` | Kann Werte per SQL ableiten oder Joins ausdrücken | `@Formula` ist ein lesender, abgeleiteter Wert; Inserts und Updates in einen Locale-Row werden dadurch nicht abgebildet. |
| `@SecondaryTable` | Ordnet Properties einer weiteren Tabelle derselben Entity zu | Dessen PK-Join modelliert denselben Entity-Schlüssel, nicht beliebig viele Rows `(page_id, locale)` für eine `Page`. |
| Eigener `EntityPersister` oder Bytecode-Enhancement | Könnte theoretisch Laden, Feldzugriff und Mutation zusammenführen | Loader/SQL-AST, Dirty Checking, Flush und Cache müssten konsistent angepasst werden. Das ist ein versionsgebundenes Integrationsprojekt, kein nachgewiesener kleiner Hook. |

Die [Interceptor-Dokumentation](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/Interceptor.html)
beschreibt die gemappten State-Arrays und das Verbot von Session-Aufrufen im
Callback. [Die `@Formula`-Dokumentation](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/Formula.html)
bezeichnet das Attribut als abgeleitet. [Jakarta `@SecondaryTable`](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/secondarytable)
verbindet Tabellen über die PK-Spalten der Parent-Entity. Hibernate unterstützt
[dynamische Entities](https://docs.hibernate.org/orm/7.4/userguide/html_single/#dynamic-model)
als separate persistente Modelle; deren Map-Zustand wird dadurch nicht automatisch
zu einem gewöhnlichen `Markdown`-Feld auf `Page`.

Der aktuelle Test hängt einen `FlushEventListener` und `AutoFlushEventListener`
**vor** Hibernates Default-Listener. Sie übertragen die Werte der Domain-Entity
auf den intern gemappten Translation-Row; Hibernate erzeugt dessen Insert und
Update selbst. Der PostgreSQL-Test prüft reines Titel-Update, Änderung von
`Markdown content` inklusive Converter, HQL-Filter auf beide Werte, Insert,
Rollback und Delete-Cascade. Ein erster Durchlauf zeigte eine wichtige Grenze:
Ohne Kenntnis der Translation-Tabelle im Query-Space war eine HQL-Abfrage vor
einem expliziten Flush veraltet. Ein `<synchronize
table="formula_page_translation"/>` im frühen XML-Overlay behebt das in
beiden Versuchen. `FormulaPage` benötigt dafür nun keine zusätzliche
Annotation. Ein Generator müsste diesen Eintrag zusammen mit den Formeln
aus `@Translation` erzeugen. [Hibernate beschreibt die Query-Space-
Abhängigkeit](https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/Synchronize.html)
für solche zusätzlichen Tabellen.

Ein weiterer Testschritt machte ein Überschreiben durch den frühen Listener
sichtbar: Nach einer Änderung von `page.title` konnte ein Editor denselben
Translation-Row ändern; ein zweiter Flush hätte den Editorwert aus dem weiter
verwalteten, älteren `Page`-Feld zurückgesetzt. Beide Test-Listener vergleichen
deshalb die aktuellen Domain-Werte mit dem beim Laden erfassten Formel-State
beziehungsweise dem zuletzt synchronisierten Wert. Ohne Änderung der Domain-
Felder schreiben sie den Translation-Row nicht an. Der PostgreSQL-Test prüft
den Editor-Update nach einem vorherigen Parent-Flush und bestätigt, dass das
verwaltete `Page`-Feld bis zu einer neuen Session unverändert bleibt. Das ist
eine begrenzte Änderungserkennung des Prototyps, noch kein Nachweis für alle
Hibernate-Flush-, weitere Refresh- und Merge-Pfade oder konkurrierende
Transaktionen.

Auch diese Listener liegen **nur im Testmodul**. Sie rufen Session-Operationen
während Flush-Callbacks auf und müssen erst auf Reentranz, Insert-/Delete-
Reihenfolge, weitere Missing-/Fallback-Konstellationen und Editor-Änderungen,
optimistische Sperren, Bulk-Operationen, Tenant-Isolation und Cache-Verhalten
geprüft werden. Ein Test nutzt eine fest deklarierte Translation-Entity, der
andere ein festes XML-Mapping für eine klassenlose Entity. Der getrennte
Bootstrap-Test erzeugt bereits Metadata aus `@Translation` und verbindet sie
mit demselben testinternen Listener. Seine allgemeine Sicherheit ist weiterhin
ungeprüft. Die Versuche belegen einen
möglichen kleineren Weg als einen eigenen Persister, noch keine sichere
allgemeine Bibliothek.

**Ergebnis:** Das normale Laden und HQL-Filtern bei unverändertem Domain-Typ ist
mit einem Formel-Mapping nachgewiesen. Für die Kombination aus automatischem
Hibernate Dirty Checking, mehreren relationalen Locale-Rows und sicherem
Schreib-/Cache-Verhalten ist in den geprüften öffentlichen Hooks weiterhin
kein vollständiger Weg nachgewiesen. Der Versuch verlagert Dirty Checking auf
die eigenständige Translation-Entity; ob dessen Vor-Flush-Synchronisierung
für alle Session-Pfade tragfähig ist, bleibt offen.
Das ist keine Aussage, dass eine tiefere Hibernate-Integration unmöglich wäre.
Falls die Vor-Flush-Synchronisierung nicht sicher gemacht werden kann, benötigt
die Lösung einen eigenen Persister oder eine vergleichbar tiefe,
versionsgebundene Erweiterung.

## Session- und Cache-Invarianten für jeden künftigen Prototyp

1. Die aktive Locale wird vor dem ersten Entity-Load an die Session gebunden.
   Ein Versuch, sie in derselben Session zu ändern, muss fehlschlagen.
2. `(entityName, pageId)` bleibt im First-Level-Cache die Parent-Identität.
   Verschiedene Locales in verschiedenen Sessions dürfen nicht denselben
   übersetzten Parent-State teilen.
3. Der Second-Level-Cache der Parent-Entity muss für lokalisierte States
   zunächst deaktiviert werden. Andernfalls könnte ein in `de` geladener
   Property-State in einer `en`-Session erscheinen. Separate Translation-
   Entities könnten später anhand `(pageId, locale)` gecacht werden, wenn
   Invalidierung und Tenant-Key korrekt sind.
   Auch der Query-Cache darf Resultate einer Locale-Formel nicht ohne Locale
   im Cache-Key zwischen Sessions teilen.
   Das opt-in Development-Profil entfernt deshalb beim Metadata-Build die
   Second-Level-Cache-Konfiguration der gesamten Entity-Hierarchie eines
   lokalisierten Parents. Unabhängige Entities bleiben cachefähig. Query-
   Caching wird in diesem Profil weiterhin abgewiesen.
4. Flush muss eine Änderung an `page.title` auch dann erkennen, wenn keine
   andere Parent-Property geändert wurde; Insert, Update, Delete und Rollback
   müssen in derselben Transaktion konsistent bleiben.
5. HQL/Criteria müssen Filter, Sortierung und Paging nach dem Locale-Wert
   ermöglichen. Bloße Post-Load-Hydrierung reicht dafür nicht.
6. Die Translation-Tabelle braucht echten PK/FK, DDL-Metadata und dieselbe
   Tenant-Isolation wie die Parent-Entity. Ein FK nur auf `page_id` genügt
   bei gemeinsamen Tenant-Tabellen nicht automatisch zur Isolation.
7. Der Admin-Zugriff auf andere Locales muss getrennten persistenten Zustand
   adressieren und darf bereits geladene Parent-Felder nicht umschalten.

## Nächste umsetzbare Wege

**Forschungsweg zum exakten Ziel:** Einen isolierten Prototyp mit eigenem
`PersisterClassResolver`/`EntityPersister` oder einer Hibernate-Core-Erweiterung
für Version 7.4.10 bauen, **falls** der Formel-/Listener-Weg keine sichere
Integration in die ActionQueue erlaubt. Der jetzt vorhandene Test ist die
kleinere Vorstufe: `Page(UUID, String title, Markdown content)`, eine Locale pro
Session, XML-Formeln und eine eigenständige Translation-Entity. Vor einer
Produktions-API müssen automatische Boot-Mappings, alle Insert-/Update-/Delete-
Ordnungen, fehlende Rows, Admin-Edits, weitere Flush-Pfade und Cache-
Korrektheit geprüft sein. Ein eigener Persister bleibt ein versionsgebundenes
Integrationsprojekt mit Wartungsstrategie.

**Weg mit gewöhnlichen Hibernate-Mappings:** Eine intern gemappte Translation-
Entity mit echtem Composite Key; die Domain-Felder bleiben per Formel im
Hibernate-State. Ein früher Contributor müsste Formel und Query-Space-
Abhängigkeit erzeugen, ein späterer Integrator den Flush-Synchronisierer
installieren. Die Locale wird beim Session-Öffnen festgelegt. Damit bleibt
`page.getContent()` der normale Zugang. Die Editor-API adressiert dagegen
die separate Translation-Entity. Dieser Weg ist im Test konkret, aber die
oben genannten offenen Invarianten verhindern noch eine Freigabe.

**Engerer Fallback:** Wenn sich die Vor-Flush-Synchronisierung nicht sicher
umsetzen lässt, kann ein kontrolliertes Repository die normalen Domain-Felder
nach dem Laden befüllen und Translation-Rows ausdrücklich speichern. Hibernate
würde dann Änderungen dieser ungemappten Felder nicht automatisch erkennen;
dieser Vertrag erfüllt die transparente Session-Semantik nur teilweise und
darf nicht als fertiges `@Translation` angeboten werden.

Bis einer dieser Wege die Invarianten erfüllt, verweigert `i18n-hibernate`
den Bootstrap einer Entity mit `@Localized`/`@Translation`. Dadurch kann
keine Annotation unbemerkt eine Spalte in `page` erzeugen oder Übersetzungen
verlieren. Die Prüfung liegt im `AdditionalMappingContributor`, also vor der
Verwendung fertiger Metadata für die Schema-Erzeugung.

## Explizites Development-Profil

Das separate Modul `i18n-development` bietet einen lokal nutzbaren, deutlich
als Prototyp abgegrenzten Weg. `DevelopmentI18n.registryBuilder()` aktiviert
die frühen XML- und späten Metadatenbeiträge über einen eigenen Bootstrap-
ClassLoaderService und filtert nur für diesen Bootstrap den produktiven Guard.
Ohne diesen Aufruf bleibt `i18n-hibernate` fail-closed. Nach dem
SessionFactory-Bau registriert `DevelopmentI18n.install()` für benannte
Translation-Properties den generischen Flush-, Load-, Refresh-, Merge- und
Delete-Listener; `openSession()` bindet genau eine Content-Locale. Ein
eigenständiger PostgreSQL-Test mit der API des Development-Artefakts prüft
`String` und konvertiertes `Markdown` über normale Domain-Felder, Schreiben,
HQL und Fallback. Die vorangestellten Listener rufen weiterhin Session-
Operationen während Flush-Callbacks auf. Query-Cache und Parent-Entity-Cache
werden für diese Integration nicht verwendet: Query-Caching wird beim
Metadata-Build abgewiesen, und der Second-Level-Cache der lokalisierten
Entity-Hierarchie wird dort deaktiviert. Unabhängige Entities bleiben
cachefähig.

Der Stack bindet mit diesem Profil alle sieben lokalisierten Entity-Typen
neben den Legacy-Translation-Entities. Sein bisheriger Development-Default
`hbm2ddl.auto=update` ergänzt nachweislich eine generierte Translation-Tabelle
zu bereits befüllten `Schedule#Event`- und Legacy-Tabellen. Für den optionalen
`hibernate-ddl-manager` 1.1.0 fehlte dagegen ein Schema-Identitätsvertrag für
klassenlose Translation-Entities. `HibernateSchemaSource.read` dereferenzierte
deren fehlende gemappte Klasse beim Lesen von `@SchemaId` und wirft eine NPE.
Ein Versuch mit einer Metadata-Sicht ohne die generierten Bindungen und
separater Erstellung der Translation-Tabelle scheitert beim zweiten Start:
`HibernateSchemaMigration.migrate` meldet den Fremdschlüssel einer unmodellierten
Tabelle auf die verwaltete Parent-Tabelle. Der ursprüngliche Laufzeittest
reproduzierte beide Fehler. `1.2.0` behebt diese Integrationsstelle:
ein SPI-Provider versieht die generierte Tabelle und ihre Spalten mit stabilen
IDs; das Schema-Modell akzeptiert den Fremdschlüssel auf den Unique Key
`(Parent-ID, Tenant-ID)` und `ON DELETE CASCADE`. Ein PostgreSQL-Test migriert
beide Tabellen und validiert den zweiten Start. Bereits von Hibernate `update`
erzeugte Tabellen benötigen vor dem Wechsel eine explizite Übernahme der
Schema-Historie.
Die generierten String-Wertspalten verwenden jetzt PostgreSQL `text`.
`DevelopmentSchemaUpgrade.widenTextColumns` erweitert alte `varchar(255)`-
Spalten ausdrücklich und transaktional, weil Hibernates `update` vorhandene
Spalten nicht zuverlässig auf `text` umstellt. Dieser
Entwicklungszugang ersetzt keinen Nachweis aller Session- und Cache-Invarianten
für eine Produktions-API.
