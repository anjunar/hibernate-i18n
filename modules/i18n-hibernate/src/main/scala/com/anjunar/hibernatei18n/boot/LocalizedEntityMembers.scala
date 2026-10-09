package com.anjunar.hibernatei18n.boot

import com.anjunar.hibernatei18n.annotation.Translation
import jakarta.persistence.{Id, MappedSuperclass}
import org.hibernate.MappingException
import org.hibernate.annotations.TenantId

import java.beans.Introspector
import java.lang.annotation.Annotation
import java.lang.reflect.{AnnotatedElement, Field, Method}

/** Narrow reflection model shared by the early XML overlay and late metadata finalizer. */
private[hibernatei18n] object LocalizedEntityMembers:
  final case class Attribute(name: String, javaType: Class[?], element: AnnotatedElement):
    def annotation[A <: Annotation](kind: Class[A]): A = element.getAnnotation(kind)
    def declaringClass: Class[?] = element match
      case field: Field => field.getDeclaringClass
      case method: Method => method.getDeclaringClass
      case _ => throw new MappingException(s"Unsupported translated member: $element")

  final case class Members(id: Attribute, translations: Seq[Attribute], tenant: Option[Attribute])

  private def getter(entity: Class[?], method: Method): Attribute =
    val name = method.getName
    if method.getParameterCount != 0 || !name.startsWith("get") || name.length <= 3 ||
        method.getReturnType == java.lang.Void.TYPE then
      throw new MappingException(s"Expected a JavaBean getter for ${entity.getName}.$name")
    val property = Introspector.decapitalize(name.substring(3))
    val setterName = "set" + name.substring(3)
    if !entity.getMethods.exists(candidate =>
      candidate.getName == setterName && candidate.getParameterCount == 1 &&
        candidate.getParameterTypes.head == method.getReturnType &&
        candidate.getReturnType == java.lang.Void.TYPE
    ) then throw new MappingException(s"Missing JavaBean setter for ${entity.getName}.$property")
    Attribute(property, method.getReturnType, method)

  def inspect(entity: Class[?]): Members =
    val hierarchy = Iterator.iterate(entity)(_.getSuperclass)
      .takeWhile(current => current != null && current != classOf[Object]).toSeq
    val idFields = hierarchy.flatMap(_.getDeclaredFields.filter(_.isAnnotationPresent(classOf[Id])))
    val idMethods = hierarchy.flatMap(_.getDeclaredMethods.filter(_.isAnnotationPresent(classOf[Id])))
    if idFields.length + idMethods.length != 1 then
      throw new MappingException("This experiment requires one @Id field or JavaBean getter")
    val translatedFields = hierarchy.flatMap(_.getDeclaredFields.filter(_.isAnnotationPresent(classOf[Translation])))
    val translatedMethods = hierarchy.flatMap(_.getDeclaredMethods.filter(_.isAnnotationPresent(classOf[Translation])))
    val tenantFields = hierarchy.flatMap(_.getDeclaredFields.filter(_.isAnnotationPresent(classOf[TenantId])))
    val tenantMethods = hierarchy.flatMap(_.getDeclaredMethods.filter(_.isAnnotationPresent(classOf[TenantId])))
    if tenantMethods.nonEmpty then
      throw new MappingException("Method-level @TenantId is not supported by this experiment")
    val (id, translations) = if idFields.nonEmpty then
      if translatedMethods.nonEmpty then
        throw new MappingException("Mixed field and getter @Translation access is not supported")
      (Attribute(idFields.head.getName, idFields.head.getType, idFields.head),
        translatedFields.toSeq.map(field => Attribute(field.getName, field.getType, field)))
    else
      if translatedFields.nonEmpty || tenantFields.nonEmpty then
        throw new MappingException("Mixed getter and field access is not supported")
      (getter(entity, idMethods.head), translatedMethods.toSeq.map(getter(entity, _)))
    if id.javaType != classOf[java.util.UUID] then
      throw new MappingException("This experiment requires one UUID @Id")
    if translations.isEmpty then throw new MappingException("@Localized entity has no @Translation fields")
    if translations.map(_.name).distinct.size != translations.size then
      throw new MappingException("Inherited @Translation names must be distinct")
    if translations.exists(attribute => attribute.declaringClass != entity &&
        !attribute.declaringClass.isAnnotationPresent(classOf[MappedSuperclass])) then
      throw new MappingException("Inherited @Translation requires a @MappedSuperclass declaring type")
    if tenantFields.length > 1 then
      throw new MappingException("This experiment supports at most one String @TenantId field")
    val tenant = tenantFields.headOption.map(field => Attribute(field.getName, field.getType, field))
    if tenant.exists(attribute => attribute.javaType != classOf[String] ||
        attribute.annotation(classOf[Translation]) != null || attribute.annotation(classOf[Id]) != null) then
      throw new MappingException("This experiment supports at most one String @TenantId field")
    Members(id, translations, tenant)
