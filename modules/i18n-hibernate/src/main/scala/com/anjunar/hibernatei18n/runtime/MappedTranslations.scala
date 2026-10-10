package com.anjunar.hibernatei18n.runtime

import com.anjunar.hibernatei18n.annotation.Localized
import com.anjunar.hibernatei18n.boot.LocalizedEntityMembers
import org.hibernate.{Hibernate, MappingException, SessionFactory}
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.`type`.BasicType
import org.hibernate.`type`.descriptor.converter.spi.BasicValueConverter

import java.util.UUID

/** Reuses the finalized Hibernate mapping, including its configured JPA AttributeConverter instances. */
private[runtime] object MappedTranslations:
  def install[P <: AnyRef](factory: SessionFactory, entityClass: Class[P]): Translations[P] =
    val implementor = factory.unwrap(classOf[SessionFactoryImplementor])
    val persister = implementor.getMappingMetamodel.findEntityDescriptor(entityClass)
    if persister == null || !entityClass.isAnnotationPresent(classOf[Localized]) then
      throw new IllegalArgumentException(s"Not a mapped @Localized entity: ${entityClass.getName}")
    val members = LocalizedEntityMembers.inspect(entityClass)
    val fields = members.translations.map { member =>
      val getter = persister.findAttributeMapping(member.name).getPropertyAccess.getGetter
      val converter = persister.getPropertyType(member.name) match
        case basic: BasicType[?] =>
          Option(basic.getValueConverter).map(_.asInstanceOf[BasicValueConverter[AnyRef, AnyRef]])
        case _ =>
          throw new MappingException(s"Expected a basic translated property: ${entityClass.getName}.${member.name}")
      if converter.exists(_.getRelationalJavaType.getJavaTypeClass != classOf[String]) then
        throw new MappingException(
          s"Translated converter must use String database values: ${entityClass.getName}.${member.name}"
        )
      TranslationField.mapped[P](
        member.name,
        page => getter.get(Hibernate.unproxy(page)),
        value => converter.fold(value)(_.toRelationalValue(value)),
        value => converter.fold(value)(_.toDomainValue(value))
      )
    }
    HibernateI18n.install(
      factory,
      entityClass,
      page => persister.getIdentifierMapping.getIdentifier(page).asInstanceOf[UUID],
      fields
    )
