package dsentric.contracts

import dsentric.codecs.DCodec
import dsentric.{RawObject, _}
import dsentric.failure._
import dsentric.operators.{Constraint, DataOperator}

private[dsentric] trait PropertyLens[D <: DObject, T] extends BaseAux with ParameterisedAux[D] {
  def _key:String
  def _path:Path
  def _codec: DCodec[T]
  def _parent: BaseContract[D]
  def _root: ContractFor[D] = _parent._root
  def _dataOperators:List[DataOperator[_]]

  /**
   * Return the property value or failure
   * Can return Default value if provided value is empty or invalid and type behaviour is empty.
   * Import to keep awareness of Traversed as path modifiers shouldnt maybe set if PathEmptyMaybe
   * @param obj
   * @return
   */
  private[contracts] def __get(rawObject:RawObject, dropBadTypes:Boolean):MaybeAvailable[T]

  /**
   * Verifies the direct property against the object.
   * Will remove from object if Not Found or Null
   * Empty objects for Object structures will also get removed.
   * @param obj
   * @return
   */
  private[contracts] def __reduce(obj: RawObject, dropBadTypes:Boolean):ValidResult[RawObject]

  private[contracts] def __reduceDelta(deltaObject:RawObject, currentObject:RawObject, dropBadTypes:Boolean):ValidResult[RawObject]

  private[contracts] def __applyConstraints[R](reduce:Available[R], badTypes: BadTypes): Available[R] =
    _dataOperators.flatMap {
      case c:Constraint[_] =>
        c.verify(_root, _path, reduce)
      case _ =>
        Nil
    } match {
      case Nil =>
        reduce
      case _ if badTypes == DropBadTypes =>
        NotFound
      case head :: tail =>
        Failed(head, tail)
    }

  private[contracts] def __applyConstraints[R](current:R, deltaReduce:DeltaReduce[R], badTypes: BadTypes): DeltaReduce[R] =
    _dataOperators.flatMap {
      case c:Constraint[_] =>
        c.verify(_root, _path, current, deltaReduce)
      case _ =>
        Nil
    } match {
      case Nil =>
        deltaReduce
      case _ if badTypes == DropBadTypes =>
        DeltaEmpty
      case head :: tail =>
        DeltaFailed(head, tail)
    }


  def $get(delta:Delta):ValidResult[Option[T]] = {
    TraversalOps.maybeTraverse(delta.value, this, false)
      .toValidOption.flatMap{
      case Some(raw) =>
        val unapply = _codec.unapply(raw)
        if (unapply.isEmpty)
          ValidResult.failure(IncorrectTypeFailure(this, raw))
         else
          ValidResult.success(unapply)

      case None =>
        ValidResult.none
    }
  }

  /**
   * Does the object satisfy all the type and expectation constraints.
   * Returns list of possible failures.
   * @param obj
   * @return
   */
  def $verify(obj:D):List[Failure] =
    __get(obj.value, false) match {
      case Failed(head, tail) =>
        head :: tail
      case _ =>
        Nil
    }

  /**
   * Sets or Replaces value for the Property.
   * Will create object path to Property if objects dont exist.
   * Function doesnt provided validation of the value being set.
   * @param value
   * @return
   */
  final def $set(value:T):PathSetter[D] =
    _codec(value) match {
      case r:RawObject@unchecked if r.isEmpty =>
        ValueDrop(_path)
      case r =>
        ValueSetter(_path, r)
    }

}


