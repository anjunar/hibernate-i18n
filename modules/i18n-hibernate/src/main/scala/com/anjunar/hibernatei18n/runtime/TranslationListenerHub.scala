package com.anjunar.hibernatei18n.runtime

import org.hibernate.event.spi.{AutoFlushEvent, AutoFlushEventListener, ClearEvent, ClearEventListener, EvictEvent, EvictEventListener, FlushEvent, FlushEventListener, MergeContext, MergeEvent, MergeEventListener, PostLoadEvent, PostLoadEventListener, PreDeleteEvent, PreDeleteEventListener, RefreshContext, RefreshEvent, RefreshEventListener, ReplicateEvent, ReplicateEventListener}

/** One registry listener per event type, delegating to each installed entity bridge. */
private[runtime] final class TranslationListenerHub extends FlushEventListener,
      AutoFlushEventListener, ClearEventListener, EvictEventListener,
      RefreshEventListener, MergeEventListener, ReplicateEventListener,
      PostLoadEventListener, PreDeleteEventListener:
  private var synchronizers = Vector.empty[TranslationSynchronizer[?]]
  private var cascadeEvictors = Vector.empty[TranslationCascadeEvictor[?]]

  def add[P <: AnyRef](
    synchronizer: TranslationSynchronizer[P],
    cascadeEvictor: TranslationCascadeEvictor[P]
  ): Unit =
    synchronizers :+= synchronizer
    cascadeEvictors :+= cascadeEvictor

  override def onFlush(event: FlushEvent): Unit = synchronizers.foreach(_.onFlush(event))
  override def onAutoFlush(event: AutoFlushEvent): Unit = synchronizers.foreach(_.onAutoFlush(event))
  override def onClear(event: ClearEvent): Unit = synchronizers.foreach(_.onClear(event))
  override def onEvict(event: EvictEvent): Unit = synchronizers.foreach(_.onEvict(event))
  override def onRefresh(event: RefreshEvent): Unit = synchronizers.foreach(_.onRefresh(event))
  override def onRefresh(event: RefreshEvent, context: RefreshContext): Unit =
    synchronizers.foreach(_.onRefresh(event, context))
  override def onMerge(event: MergeEvent): Unit = synchronizers.foreach(_.onMerge(event))
  override def onMerge(event: MergeEvent, context: MergeContext): Unit =
    synchronizers.foreach(_.onMerge(event, context))
  override def onReplicate(event: ReplicateEvent): Unit = synchronizers.foreach(_.onReplicate(event))
  override def onPostLoad(event: PostLoadEvent): Unit = synchronizers.foreach(_.onPostLoad(event))
  override def onPreDelete(event: PreDeleteEvent): Boolean =
    cascadeEvictors.foreach(_.onPreDelete(event))
    false
