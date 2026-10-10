import sbt.url

// sbt 2 applies bare settings to every subproject, including the root.
version := "1.1.0"
organization := "com.anjunar"
organizationName := "Anjunar"
organizationHomepage := Some(url("https://github.com/anjunar"))

scalaVersion := "3.9.0"
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17")
javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8")
libraryDependencies += "org.scalameta" %% "munit" % "1.2.0" % Test

homepage := Some(url("https://github.com/anjunar/hibernate-i18n"))
description := "Relational translations integrated with Hibernate ORM metadata and entity lifecycle."
licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
scmInfo := Some(ScmInfo(
  url("https://github.com/anjunar/hibernate-i18n"),
  "scm:git:https://github.com/anjunar/hibernate-i18n.git",
  Some("scm:git:git@github.com:anjunar/hibernate-i18n.git")
))
developers := List(Developer(
  id = "anjunar",
  name = "Patrick Bittner",
  email = "anjunar@gmx.de",
  url = url("https://github.com/anjunar")
))
versionScheme := Some("early-semver")
pomIncludeRepository := { _ => false }
publishMavenStyle := true
crossPaths := false
publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

// Avoid locked dependency JARs when rebuilding on Windows with the sbt server.
exportJars := false

lazy val hibernateVersion = "7.4.10.Final"
lazy val verifyRuntimeJar = taskKey[Unit]("Verify the production translation API, bootstrap guard and schema provider in the published JAR")

lazy val root = (project in file("."))
  .aggregate(i18nCore, i18nHibernate, i18nTests)
  .settings(
    name := "hibernate-i18n-build",
    publish / skip := true
  )

lazy val i18nCore = (project in file("modules/i18n-core"))
  .settings(
    name := "hibernate-i18n-core",
    description := "Translation annotations independent of Hibernate and web frameworks.",
    autoScalaLibrary := false
  )

lazy val i18nHibernate = (project in file("modules/i18n-hibernate"))
  .dependsOn(i18nCore)
  .settings(
    name := "hibernate-i18n",
    description := "Relational entity translations, locale-bound Hibernate Sessions and typed translation editors.",
    libraryDependencies ++= Seq(
      "org.hibernate.orm" % "hibernate-core" % hibernateVersion,
      "io.zonky.test" % "embedded-postgres" % "2.2.2" % Test,
      "com.anjunar.hibernateddl" %% "schema-hibernate" % "1.2.0",
      "com.anjunar.hibernateddl" %% "schema-integration" % "1.2.0"
    ),
    Test / fork := true,
    Test / parallelExecution := false,
    verifyRuntimeJar := Def.uncached {
      val jar = (Compile / packageBin).value
      val archive = new java.util.zip.ZipFile(fileConverter.value.toPath(jar).toFile)
      try {
        val guardService = "META-INF/services/org.hibernate.boot.spi.AdditionalMappingContributor"
        val descriptor = archive.getEntry(guardService)
        if (descriptor == null) sys.error(s"Missing $guardService in $jar")
        val stream = archive.getInputStream(descriptor)
        val providers = try {
          scala.io.Source.fromInputStream(stream, "UTF-8").getLines()
            .map(_.takeWhile(_ != '#').trim).filter(_.nonEmpty).toList
        } finally stream.close()
        if (providers != List("com.anjunar.hibernatei18n.boot.LocalizedBootstrapGuard"))
          sys.error(s"Unexpected Hibernate bootstrap services in $jar: $providers")
        if (archive.getEntry("META-INF/services/org.hibernate.boot.spi.MetadataSourcesContributor") != null)
          sys.error(s"Automatic metadata contributor bypasses the explicit locale bootstrap in $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/boot/TranslationMappingXml$.class") == null)
          sys.error(s"Internal mapping generator missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/boot/LocalizedEntityMembers$.class") == null)
          sys.error(s"Internal localized member inspection missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/boot/TranslationMetadataFinalizer$.class") == null)
          sys.error(s"Internal metadata finalizer missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/boot/TranslationCachePolicy$.class") == null)
          sys.error(s"Internal translation cache policy missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/runtime/SessionContentLocale$.class") == null)
          sys.error(s"Internal Session locale binding missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/runtime/ManagedTranslationRows$.class") == null)
          sys.error(s"Internal managed translation-row lookup missing from $jar")
        if (archive.getEntry("com/anjunar/hibernatei18n/runtime/MappedTranslations$.class") == null)
          sys.error(s"Annotation-derived runtime registration missing from $jar")
        List("HibernateI18n$", "TranslationField$", "Translations", "TranslationSchemaUpgrade$").foreach { api =>
          if (archive.getEntry(s"com/anjunar/hibernatei18n/runtime/$api.class") == null)
            sys.error(s"Missing production translation API $api in $jar")
        }
        val schemaProvider = "META-INF/services/com.anjunar.hibernateddl.hibernate.ClasslessEntitySchemaIdProvider"
        if (archive.getEntry(schemaProvider) == null)
          sys.error(s"Missing generated translation schema-ID provider in $jar")
        val entries = archive.entries()
        while (entries.hasMoreElements) {
          val path = entries.nextElement().getName
          if (path.startsWith("com/anjunar/hibernatei18n/development/"))
            sys.error(s"Development API leaked into $jar: $path")
        }
      } finally archive.close()
    }
  )

// Test infrastructure is never published or pulled into application dependencies.
lazy val i18nTests = (project in file("modules/i18n-tests"))
  .dependsOn(i18nHibernate)
  .settings(
    name := "i18n-tests",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.hibernate.orm" % "hibernate-envers" % hibernateVersion % Test,
      "io.zonky.test" % "embedded-postgres" % "2.2.2" % Test
    ),
    Test / fork := true,
    Test / parallelExecution := false
  )

// sbt 2's testFull executes every test instead of replaying cached results.
addCommandAlias("check", ";clean;testFull;i18nHibernate/verifyRuntimeJar")
