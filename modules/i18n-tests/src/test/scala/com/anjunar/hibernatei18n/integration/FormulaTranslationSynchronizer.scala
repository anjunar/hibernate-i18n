package com.anjunar.hibernatei18n.integration

import com.anjunar.hibernatei18n.runtime.SessionContentLocale
import org.hibernate.Session
import org.hibernate.engine.spi.Status
import org.hibernate.event.spi.{AutoFlushEvent, AutoFlushEventListener, EventSource, FlushEvent, FlushEventListener}

import scala.collection.mutable

/**
 * Experimental pre-flush synchronization with a mapped translation entity. This must remain
 * test-only until reentrant loads, action ordering, auto-flush and caches are fully verified.
 */
final class FormulaTranslationSynchronizer extends FlushEventListener, AutoFlushEventListener:
  private val active = mutable.Set.empty[Session]
  private val changes = new TranslationChangeTracker[FormulaPage](_.title, _.content)

  def bind(session: Session, locale: String): Unit = SessionContentLocale.bind(session, locale)

  override def onFlush(event: FlushEvent): Unit = synchronize(event.getSession)

  override def onAutoFlush(event: AutoFlushEvent): Unit = synchronize(event.getSession)

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
        case entry if entry.getKey.isInstanceOf[FormulaPage] && entry.getValue.getStatus == Status.MANAGED =>
          (entry.getKey.asInstanceOf[FormulaPage], entry.getValue)
      }
      pages.foreach { (page, entry) =>
        val changed = changes.changed(session, page, entry)
        if page.id != null && changed.any then
          val locale = SessionContentLocale.required(session)
          val id = new FormulaTranslationId()
          id.pageId = page.id
          id.locale = locale
          val translation = if entry.isExistsInDatabase then session.find(classOf[FormulaPageTranslation], id) else null
          if translation == null then
            if page.title != null || page.content != null then
              val created = new FormulaPageTranslation()
              created.id = id
              created.page = page
              if changed.title then created.title = page.title
              if changed.content then created.content = page.content
              session.persist(created)
          else
            if changed.title then translation.title = page.title
            if changed.content then translation.content = page.content
          changes.synchronizedValue(session, page)
      }
    finally synchronized { active -= session }
