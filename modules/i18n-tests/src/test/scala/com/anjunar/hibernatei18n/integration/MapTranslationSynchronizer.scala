package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.{ManagedTranslationRows, SessionContentLocale}
import org.hibernate.{HibernateException, Session}
import org.hibernate.engine.spi.Status
import org.hibernate.event.spi.{AutoFlushEvent, AutoFlushEventListener, ClearEvent, ClearEventListener, EventSource, EvictEvent, EvictEventListener, FlushEvent, FlushEventListener, MergeContext, MergeEvent, MergeEventListener, PostLoadEvent, PostLoadEventListener, RefreshContext, RefreshEvent, RefreshEventListener, ReplicateEvent, ReplicateEventListener}

import java.util
import scala.collection.mutable

/** Test-only bridge between ordinary fields and a classless translation entity. */
final class MapTranslationSynchronizer[P <: AnyRef](
  entityClass: Class[P],
  translationEntity: String,
  idOf: P => util.UUID,
  titleOf: P => String,
  contentOf: P => Markdown
) extends FlushEventListener, AutoFlushEventListener, ClearEventListener, EvictEventListener, RefreshEventListener,
      MergeEventListener, ReplicateEventListener, PostLoadEventListener:
  private val active = mutable.Set.empty[Session]
  private val changes = new TranslationChangeTracker[P](titleOf, contentOf)

  def bind(session: Session, locale: String): Unit = SessionContentLocale.bind(session, locale)

  override def onFlush(event: FlushEvent): Unit = synchronize(event.getSession)

  override def onAutoFlush(event: AutoFlushEvent): Unit = synchronize(event.getSession)

  override def onClear(event: ClearEvent): Unit = changes.cleared(event.getSession)

  override def onEvict(event: EvictEvent): Unit = changes.evicted(event.getSession, event.getObject)

  override def onRefresh(event: RefreshEvent): Unit = refreshed(event)

  override def onRefresh(event: RefreshEvent, context: RefreshContext): Unit = refreshed(event)

  override def onMerge(event: MergeEvent): Unit = checkMergeState(event)

  override def onMerge(event: MergeEvent, context: MergeContext): Unit = checkMergeState(event)

  override def onReplicate(event: ReplicateEvent): Unit =
    if entityClass.isInstance(event.getObject) then
      SessionContentLocale.required(event.getSession)
      throw new HibernateException(
        "Cannot replicate a localized entity; load it in the target content locale and edit the managed instance"
      )

  override def onPostLoad(event: PostLoadEvent): Unit =
    if entityClass.isInstance(event.getEntity) then
      val session = event.getSession
      val locale = SessionContentLocale.required(session)
      val page = entityClass.cast(event.getEntity)
      val id = new util.HashMap[String, Object]()
      id.put("pageId", idOf(page))
      id.put("locale", locale)
      ManagedTranslationRows.find(session, translationEntity, id)

  private def checkMergeState(event: MergeEvent): Unit =
    if entityClass.isInstance(event.getOriginal) then
      val session = event.getSession
      SessionContentLocale.required(session)
      if !session.contains(event.getOriginal) then
        // A copied formula value may be from another locale or an obsolete fallback row.
        throw new HibernateException(
          "Cannot merge an unmanaged localized entity; load it in the target content locale and edit the managed instance, or persist a new entity"
        )

  private def refreshed(event: RefreshEvent): Unit =
    if entityClass.isInstance(event.getObject) then
      val session = event.getSession
      val page = entityClass.cast(event.getObject)
      val locale = SessionContentLocale.required(session)
      val id = new util.HashMap[String, Object]()
      id.put("pageId", idOf(page))
      id.put("locale", locale)
      val persistenceContext = session.getPersistenceContextInternal
      val translation = ManagedTranslationRows.managed(session, translationEntity, id).orNull
      if translation != null then
        val persister = session.getFactory.getMappingMetamodel.getEntityDescriptor(translationEntity)
        val entry = persistenceContext.getEntry(translation)
        if entry == null || entry.getLoadedState == null then
          throw new HibernateException("Cannot refresh a Page with an unsaved translation row")
        val dirty = persister.findDirty(persister.getValues(translation), entry.getLoadedState, translation, session)
        if dirty != null && dirty.nonEmpty then
          throw new HibernateException("Cannot refresh a Page with a modified translation row")
        session.evict(translation)
      // Capture the version belonging to the freshly read Page state before a later flush.
      ManagedTranslationRows.find(session, translationEntity, id)
      changes.synchronizedValue(session, page)

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
        val changed = changes.changed(session, page, entry)
        if idOf(page) != null && changed.any then
          val id = new util.HashMap[String, Object]()
          id.put("pageId", idOf(page))
          id.put("locale", locale)
          val translation =
            if entry.isExistsInDatabase then ManagedTranslationRows.find(session, translationEntity, id) else null
          if translation == null then
            if (changed.title && titleOf(page) != null) || (changed.content && contentOf(page) != null) then
              val created = new util.HashMap[String, Object]()
              created.putAll(id)
              if changed.title then created.put("title", titleOf(page))
              if changed.content then
                created.put("content", if contentOf(page) == null then null else contentOf(page).source)
              session.persist(translationEntity, created)
          else
            rejectOverlappingEditorChanges(session, translation, changed)
            if changed.title then translation.put("title", titleOf(page))
            if changed.content then
              translation.put("content", if contentOf(page) == null then null else contentOf(page).source)
            if translation.get("title") == null && translation.get("content") == null then
              session.remove(translation)
          changes.synchronizedValue(session, page)
      }
    finally synchronized { active -= session }

  private def rejectOverlappingEditorChanges(
    session: EventSource,
    translation: util.Map[String, Object],
    changed: TranslationChangeTracker.Changes
  ): Unit =
    val entry = session.getPersistenceContextInternal.getEntry(translation)
    if entry == null || entry.getStatus != Status.MANAGED || entry.getLoadedState == null then
      throw new HibernateException("Cannot combine a domain edit with an unsaved translation row")
    val persister = entry.getPersister
    val dirty = persister.findDirty(persister.getValues(translation), entry.getLoadedState, translation, session)
    if dirty != null then
      val names = persister.getPropertyNames
      val overlap = dirty.iterator.map(names(_)).find { name =>
        (name == "title" && changed.title) || (name == "content" && changed.content)
      }
      overlap.foreach(name => throw new HibernateException(s"Conflicting translation edit of '$name'"))
