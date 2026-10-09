package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.{LocalizedEntityMembers, TranslationMappingXml, LocalizedBootstrapGuard}
import com.anjunar.hibernatei18n.runtime.SessionContentLocale
import org.hibernate.{HibernateException, Session, SessionFactory}
import org.hibernate.boot.registry.{BootstrapServiceRegistryBuilder, StandardServiceRegistryBuilder}
import org.hibernate.boot.registry.classloading.internal.ClassLoaderServiceImpl
import org.hibernate.boot.spi.{AdditionalMappingContributor, MetadataSourcesContributor}
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.event.service.spi.EventListenerRegistry
import org.hibernate.event.spi.EventType

import java.util.UUID
import java.util.WeakHashMap
import java.util.function.UnaryOperator
import scala.jdk.CollectionConverters.*

/** Explicit bootstrap for the supported Hibernate translation runtime. */
object HibernateI18n:
  private final class Registration(
    val hub: TranslationListenerHub,
    var installed: Set[Class[?]]
  )

  private val registrations = new WeakHashMap[SessionFactory, Registration]()

  private def localizedEntities(factory: SessionFactory): Set[Class[?]] =
    factory.getMetamodel.getEntities.asScala.iterator
      .flatMap(entity => Option(entity.getJavaType))
      .filter(_.isAnnotationPresent(classOf[Localized])).toSet

  def registryBuilder(): StandardServiceRegistryBuilder =
    val classLoading = new ClassLoaderServiceImpl():
      override def loadJavaServices[S](contract: Class[S]): java.util.Collection[S] =
        val discovered = super.loadJavaServices(contract).asScala.toSeq
        val providers: Seq[S] =
          if contract == classOf[MetadataSourcesContributor] then
            discovered :+ new TranslationMappingSources().asInstanceOf[S]
          else if contract == classOf[AdditionalMappingContributor] then
            discovered.filterNot(_.getClass == classOf[LocalizedBootstrapGuard]) :+
              new TranslationMetadata().asInstanceOf[S]
          else discovered
        providers.asJava
    val bootstrap = new BootstrapServiceRegistryBuilder().applyClassLoaderService(classLoading).build()
    new StandardServiceRegistryBuilder(bootstrap)

  def install[P <: AnyRef](
    factory: SessionFactory,
    entityClass: Class[P],
    idOf: P => UUID,
    fields: Seq[TranslationField[P, ?]]
  ): Translations[P] = synchronized {
    if !localizedEntities(factory).contains(entityClass) then
      throw new IllegalArgumentException(s"Not a mapped @Localized entity: ${entityClass.getName}")
    val existing = registrations.get(factory)
    if existing != null && existing.installed.contains(entityClass) then
      throw new IllegalStateException(s"Runtime listeners are already installed for ${entityClass.getName}")
    val expected = LocalizedEntityMembers.inspect(entityClass).translations.map(_.name).toSet
    if fields.map(_.name).toSet != expected || fields.map(_.name).distinct.size != fields.size then
      throw new IllegalArgumentException(s"Runtime fields must match @Translation members of ${entityClass.getName}")
    val translationEntity = TranslationMappingXml.translationEntityName(entityClass)
    if factory.unwrap(classOf[SessionFactoryImplementor]).getMappingMetamodel
        .getEntityDescriptor(translationEntity) == null then
      throw new HibernateException(s"Missing generated translation entity: $translationEntity")
    val synchronizer = new TranslationSynchronizer(entityClass, translationEntity, idOf, fields)
    val registration = if existing != null then existing else
      val hub = new TranslationListenerHub()
      val listeners = factory.unwrap(classOf[SessionFactoryImplementor]).getServiceRegistry
        .getService(classOf[EventListenerRegistry])
      listeners.prependListeners(EventType.FLUSH, hub)
      listeners.prependListeners(EventType.AUTO_FLUSH, hub)
      listeners.appendListeners(EventType.CLEAR, hub)
      listeners.appendListeners(EventType.EVICT, hub)
      listeners.appendListeners(EventType.REFRESH, hub)
      listeners.prependListeners(EventType.MERGE, hub)
      listeners.prependListeners(EventType.REPLICATE, hub)
      listeners.appendListeners(EventType.POST_LOAD, hub)
      listeners.appendListeners(EventType.PRE_DELETE, hub)
      val created = new Registration(hub, Set.empty)
      registrations.put(factory, created)
      created
    registration.hub.add(synchronizer,
      new TranslationCascadeEvictor(entityClass, translationEntity, idOf))
    registration.installed += entityClass
    new Translations(factory, entityClass, translationEntity, idOf, fields)
  }

  def openSession(factory: SessionFactory, locale: String): Session =
    openSessionForTenant(factory, locale, None)

  def openSession(factory: SessionFactory, locale: String, tenantId: String): Session =
    require(tenantId != null && tenantId.nonEmpty, "A tenant identifier is required")
    openSessionForTenant(factory, locale, Some(tenantId))

  private def openSessionForTenant(
    factory: SessionFactory,
    locale: String,
    tenantId: Option[String]
  ): Session =
    synchronized {
      val installed = Option(registrations.get(factory)).map(_.installed).getOrElse(Set.empty)
      if installed.isEmpty then
        throw new IllegalStateException("Install translation listeners before opening a localized Session")
      val missing = localizedEntities(factory).diff(installed)
      if missing.nonEmpty then
        throw new IllegalStateException(s"Missing translation listeners for: ${missing.toSeq.map(_.getName).sorted.mkString(", ")}")
    }
    val options = factory.withOptions().statementInspector(new LocaleInspector(locale))
    tenantId.foreach(value => options.tenantIdentifier(value.asInstanceOf[Object]))
    val session = options.openSession()
    try
      SessionContentLocale.bind(session, locale)
      session
    catch
      case error: Throwable =>
        session.close()
        throw error

