package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.runtime.{ManagedTranslationRows, SessionContentLocale}
import org.hibernate.{HibernateException, Session}
import org.hibernate.engine.spi.{EntityEntry, Status}
import org.hibernate.event.spi.{AutoFlushEvent, AutoFlushEventListener, ClearEvent, ClearEventListener, EventSource, EvictEvent, EvictEventListener, FlushEvent, FlushEventListener, MergeContext, MergeEvent, MergeEventListener, PostLoadEvent, PostLoadEventListener, RefreshContext, RefreshEvent, RefreshEventListener, ReplicateEvent, ReplicateEventListener}

import java.util
import scala.collection.mutable

/** Synchronizes managed domain fields with the exact-locale translation row during flush. */
private[runtime] final class TranslationSynchronizer[P <: AnyRef](
  entityClass: Class[P],
  translationEntity: String,
  idOf: P => util.UUID,
  fields: Seq[TranslationField[P, ?]]
) extends FlushEventListener, AutoFlushEventListener, ClearEventListener, EvictEventListener,
      RefreshEventListener, MergeEventListener, ReplicateEventListener, PostLoadEventListener:
  private val active = mutable.Set.empty[Session]
  private val snapshots = new util.WeakHashMap[EventSource, util.IdentityHashMap[P, Vector[Any]]]()

  override def onFlush(event: FlushEvent): Unit = synchronize(event.getSession)
  override def onAutoFlush(event: AutoFlushEvent): Unit = synchronize(event.getSession)
  override def onClear(event: ClearEvent): Unit = synchronized { snapshots.remove(event.getSession) }
  override def onEvict(event: EvictEvent): Unit = synchronized {
    val values = snapshots.get(event.getSession)
    if values != null then
      values.remove(event.getObject)
      if values.isEmpty then snapshots.remove(event.getSession)
  }
  override def onRefresh(event: RefreshEvent): Unit = refreshed(event)
  override def onRefresh(event: RefreshEvent, context: RefreshContext): Unit = refreshed(event)
  override def onMerge(event: MergeEvent): Unit = checkMerge(event)
  override def onMerge(event: MergeEvent, context: MergeContext): Unit = checkMerge(event)
  override def onReplicate(event: ReplicateEvent): Unit =
    if entityClass.isInstance(event.getObject) then
      SessionContentLocale.required(event.getSession)
      throw new HibernateException(
        "Cannot replicate a localized entity; load and edit managed state in its target locale"
      )

  override def onPostLoad(event: PostLoadEvent): Unit =
    if entityClass.isInstance(event.getEntity) then
      val page = entityClass.cast(event.getEntity)
      val locale = SessionContentLocale.required(event.getSession)
      ManagedTranslationRows.find(event.getSession, translationEntity, rowId(idOf(page), locale))

  private def rowId(pageId: util.UUID, locale: String): util.HashMap[String, Object] =
    val id = new util.HashMap[String, Object]()
    id.put("pageId", pageId)
    id.put("locale", locale)
    id

  private def values(page: P): Vector[Any] = fields.iterator.map(_.readAny(page)).toVector

  private def verifyReaders(page: P, entry: EntityEntry): Unit =
    val persister = entry.getPersister
    val names = persister.getPropertyNames
    val actual = persister.getValues(page)
    fields.foreach { field =>
      val index = names.indexOf(field.name)
      if index < 0 || !util.Objects.equals(actual(index), field.readAny(page)) then
        throw new HibernateException(
          s"Runtime field reader for ${entityClass.getName}.${field.name} does not match the mapped property"
        )
    }

  private def initialValues(entry: EntityEntry): Vector[Any] =
    if !entry.isExistsInDatabase then Vector.fill(fields.size)(null)
    else
      val loaded = entry.getLoadedState
      if loaded == null then throw new HibernateException("Formula translation state has no loaded snapshot")
      val names = entry.getPersister.getPropertyNames
      fields.iterator.map { field =>
        val index = names.indexOf(field.name)
        if index < 0 then throw new HibernateException(s"Missing formula property: ${field.name}")
        loaded(index)
      }.toVector

  private def changed(session: EventSource, page: P, entry: EntityEntry): Vector[Boolean] = synchronized {
    val previous = Option(snapshots.get(session)).flatMap(values => Option(values.get(page)))
      .getOrElse(initialValues(entry))
    previous.zip(values(page)).map((before, after) => !util.Objects.equals(before, after))
  }

  private def remember(session: EventSource, page: P): Unit = synchronized {
    var entries = snapshots.get(session)
    if entries == null then
      entries = new util.IdentityHashMap[P, Vector[Any]]()
      snapshots.put(session, entries)
    entries.put(page, values(page))
  }

  private def checkMerge(event: MergeEvent): Unit =
    if entityClass.isInstance(event.getOriginal) then
      SessionContentLocale.required(event.getSession)
      if !event.getSession.contains(event.getOriginal) then
        throw new HibernateException("Cannot merge an unmanaged localized entity; load it in the target content locale")

  private def refreshed(event: RefreshEvent): Unit =
    if entityClass.isInstance(event.getObject) then
      val session = event.getSession
      val page = entityClass.cast(event.getObject)
      val locale = SessionContentLocale.required(session)
      val id = rowId(idOf(page), locale)
      val translation = ManagedTranslationRows.managed(session, translationEntity, id).orNull
      if translation != null then
        val entry = session.getPersistenceContextInternal.getEntry(translation)
        if entry == null || entry.getLoadedState == null then
          throw new HibernateException("Cannot refresh a Page with an unsaved translation row")
        val persister = entry.getPersister
        val dirty = persister.findDirty(persister.getValues(translation), entry.getLoadedState, translation, session)
        if dirty != null && dirty.nonEmpty then
          throw new HibernateException("Cannot refresh a Page with a modified translation row")
        session.evict(translation)
      ManagedTranslationRows.find(session, translationEntity, id)
      remember(session, page)

  private def synchronize(session: EventSource): Unit =
    val entered = synchronized {
      if active.contains(session) then false
      else
        active += session
        true
    }
    if !entered then return
    try
      val pages = session.getPersistenceContextInternal.reentrantSafeEntityEntries().collect {
        case entry if entityClass.isInstance(entry.getKey) && entry.getValue.getStatus == Status.MANAGED =>
          (entityClass.cast(entry.getKey), entry.getValue)
      }
      val locale = if pages.nonEmpty then SessionContentLocale.required(session) else null
      pages.foreach { (page, entry) =>
        verifyReaders(page, entry)
        val differences = changed(session, page, entry)
        if idOf(page) != null && differences.exists(identity) then
          val id = rowId(idOf(page), locale)
          val translation = if entry.isExistsInDatabase then
            ManagedTranslationRows.find(session, translationEntity, id)
          else null
          if translation == null then
            if fields.indices.exists(index => differences(index) && fields(index).readAny(page) != null) then
              val created = new util.HashMap[String, Object]()
              created.putAll(id)
              fields.indices.filter(differences).foreach { index =>
                val field = fields(index)
                created.put(field.name, field.encodeAny(field.readAny(page)))
              }
              session.persist(translationEntity, created)
          else
            rejectOverlappingEditorChanges(session, translation, differences)
            fields.indices.filter(differences).foreach { index =>
              val field = fields(index)
              translation.put(field.name, field.encodeAny(field.readAny(page)))
            }
            if fields.forall(field => translation.get(field.name) == null) then session.remove(translation)
          remember(session, page)
      }
    finally synchronized { active -= session }

  private def rejectOverlappingEditorChanges(
    session: EventSource,
    translation: util.Map[String, Object],
    differences: Vector[Boolean]
  ): Unit =
    val entry = session.getPersistenceContextInternal.getEntry(translation)
    if entry == null || entry.getStatus != Status.MANAGED || entry.getLoadedState == null then
      throw new HibernateException("Cannot combine a domain edit with an unsaved translation row")
    val persister = entry.getPersister
    val dirty = persister.findDirty(persister.getValues(translation), entry.getLoadedState, translation, session)
    if dirty != null then
      val names = persister.getPropertyNames
      val changedNames = fields.indices.filter(differences).map(fields(_).name).toSet
      dirty.iterator.map(names(_)).find(changedNames.contains).foreach { name =>
        throw new HibernateException(s"Conflicting translation edit of '$name'")
      }
