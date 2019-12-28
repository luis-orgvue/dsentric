package dsentric.operators

import dsentric.contracts.ContractFor
import dsentric.failure.{ValidResult, ValidationFailures}
import dsentric.{DObject, Data, Path, Raw}
import dsentric.schema.{ObjectDefinition, TypeDefinition}

sealed trait DataOperator[+T]

sealed trait Validator[+T] extends DataOperator[T]{

  def apply[A <: TypeDefinition](t:A, objDefs:Vector[ObjectDefinition], forceNested:Boolean):(A, Vector[ObjectDefinition]) =
    withObjsDefinition(forceNested).lift(t -> objDefs).getOrElse(t -> objDefs).asInstanceOf[(A, Vector[ObjectDefinition])]

  def withObjsDefinition(forceNested:Boolean):PartialFunction[(TypeDefinition, Vector[ObjectDefinition]), (TypeDefinition, Vector[ObjectDefinition])] = {
    case (t, o) if definition.isDefinedAt(t) => definition(t) -> o
  }

  def definition:PartialFunction[TypeDefinition, TypeDefinition] = {
    case t => t
  }

}

trait RawValidator[+T] extends Validator[T] {

  def apply[D <: DObject](contract:ContractFor[D], path:Path, value:Option[Raw], currentState:Option[Raw]):ValidationFailures
}

trait ValueValidator[+T] extends Validator[T] {

  def apply[S >: T, D <: DObject](contract:ContractFor[D], path:Path, value:Option[S], currentState: => Option[S]): ValidationFailures

  def &&[S >: T] (v:ValueValidator[S]):ValueValidator[S] =
    AndValidator(this, v)

  def ||[S >: T] (v:ValueValidator[S]):ValueValidator[S] =
    OrValidator(this, v)
}

trait ContextValidator[+T] extends DataOperator[T] {
  def apply[S >: T](path:Path, value:Option[S], currentState: => Option[S]): ValidationFailures
}

trait Transform[+T] extends DataOperator[T] {
  def transform[S >: T](value: Option[S]):Option[S]
}

trait ContextTransform[C, +T] extends DataOperator[T] {
  def transform[S >: T](context:C, value:Option[S]):Option[S]
}

trait Sanitizer[+T] extends DataOperator[T] {

  def sanitize(value:Option[Raw]):Option[Raw]
}


