package com.anjunar.hibernatei18n.integration

import org.hibernate.boot.spi.SessionFactoryOptions
import org.hibernate.cache.cfg.spi.{DomainDataRegionBuildingContext, DomainDataRegionConfig}
import org.hibernate.cache.spi.support.{DomainDataStorageAccess, RegionFactoryTemplate, StorageAccess}
import org.hibernate.engine.spi.{SessionFactoryImplementor, SharedSessionContractImplementor}

import java.util.concurrent.ConcurrentHashMap

/** Minimal test-only cache provider for observing Hibernate's actual query-cache behavior. */
final class InMemoryCacheRegionFactory extends RegionFactoryTemplate:
  private val regions = new ConcurrentHashMap[String, ConcurrentHashMap[Object, Object]]()

  override protected def prepareForUse(settings: SessionFactoryOptions, configValues: java.util.Map[String, Object]): Unit = ()

  override protected def releaseFromUse(): Unit = regions.clear()

  override protected def createDomainDataStorageAccess(
      regionConfig: DomainDataRegionConfig,
      buildingContext: DomainDataRegionBuildingContext
  ): DomainDataStorageAccess = storageFor(regionConfig.getRegionName)

  override protected def createQueryResultsRegionStorageAccess(
      regionName: String,
      sessionFactory: SessionFactoryImplementor
  ): StorageAccess = storageFor(regionName)

  override protected def createTimestampsRegionStorageAccess(
      regionName: String,
      sessionFactory: SessionFactoryImplementor
  ): StorageAccess = storageFor(regionName)

  private def storageFor(regionName: String): DomainDataStorageAccess =
    val entries = regions.computeIfAbsent(regionName, _ => new ConcurrentHashMap[Object, Object]())
    new DomainDataStorageAccess:
      override def getFromCache(key: Object, session: SharedSessionContractImplementor): Object = entries.get(key)
      override def putIntoCache(key: Object, value: Object, session: SharedSessionContractImplementor): Unit =
        entries.put(key, value)
        ()
      override def contains(key: Object): Boolean = entries.containsKey(key)
      override def evictData(): Unit = entries.clear()
      override def evictData(key: Object): Unit =
        entries.remove(key)
        ()
      override def release(): Unit = entries.clear()
