package dsentric.contracts

import dsentric._
import cats.data._
import dsentric.codecs.DCodec
import dsentric.codecs.std.DCodecs
import dsentric.failure.{ClosedContractFailure, IncorrectTypeFailure, StructuralFailure, ValidResult, ValidStructural}

private[dsentric] sealed trait ObjectPropertyLens[D <: DObject]
  extends BaseContract[D] with PropertyLens[D, DObject]{

  def _codec: DCodec[DObject]

  private[contracts] def isIgnore2BadTypes(dropBadTypes:Boolean):BadTypes =
    if (dropBadTypes) DropBadTypes
    else FailOnBadTypes

  private[contracts] def __reduce(obj: RawObject, dropBadTypes:Boolean):ValidStructural[RawObject] = {
    def reduce(rawObject:Map[String, Any]):ValidStructural[RawObject] =
      ObjectPropertyLensOps.reduce(this, rawObject, isIgnore2BadTypes(dropBadTypes))

    obj.get(_key) match {
      case None =>
        reduce(RawObject.empty).map{r =>
          if (r.isEmpty) obj
          else obj + (_key -> r)
        }
      case Some(rawObject:RawObject) =>
        reduce(rawObject).map{r =>
          if (r.isEmpty) obj - _key
          else obj + (_key -> r)
        }
    }
  }

  /**
   * Apply object contract to modify the object
   * @param f
   * @return
   */
  final def $modify(f:this.type => D => D):D => D =
    f(this)

  /**
   * Apply object contract to modify the object where there are
   * changes requiring verification
   * @param f
   * @return
   */
  final def $verifyModify(f:this.type => D => ValidResult[D]):D => ValidResult[D] =
    f(this)

  /**
   * Verifies the structure of the object against its properties
   * and additional property definition returning a list of possibly failures.
   * @param obj
   * @return
   */
  def $verify(obj:D):List[StructuralFailure]

  /**
   * Returns object for this property.
   * Will return failure if any of the properties would fail
   * or the additional properties are invalid
   * Returns None if there is a path with Maybes Object Properties that have no values
   * Default properties that havent been defined will be provided in the object
   *
   * Tricky expected missing on maybe when no object vs expected missing when there is an object....
   *
   *
   * @param obj
   * @return
   */
  final def $get(obj:D, dropBadTypes:Boolean = false):ValidResult[Option[DObject]] =
    __get(obj, dropBadTypes).toValidOption
  /**
   * Sets the object content to the passed value.
   * Does nothing if None is passed.
   * Verifies the object satisfies the property requirements
   * and additional properties definition.
   * @param obj
   * @return
   */
  final def $maybeSet(obj:Option[DObject]):ValidPathSetter[D] =
    obj.fold[ValidPathSetter[D]](IdentityValidSetter[D]())($set)
}

sealed trait ExpectedObjectPropertyLensLike[D <: DObject] extends ObjectPropertyLens[D] {

}
/**
 * An Expected object doesnt necessarily have to be present if none of its properties
 * are expected.
 * @tparam D
 */
private[dsentric] trait ExpectedObjectPropertyLens[D <: DObject] extends ExpectedObjectPropertyLensLike[D]  with ApplicativeLens[D, DObject]{

  private[contracts] def __get(data:D, dropBadTypes:Boolean):Valid[DObject] = {
    def reduce(rawObject:Map[String, Any]):Valid[DObject] =
      ObjectPropertyLensOps.reduce(this, rawObject, isIgnore2BadTypes(dropBadTypes)) match {
        case Right(rawObject) =>
          //its possible this will return an empty object.
          Found(new DObjectInst(rawObject))
        case Left(NonEmptyList(head, tail)) =>
          Failed(head, tail)
      }

    TraversalOps.traverse(data.value, this, dropBadTypes) match {
      case NotFound  =>
        reduce(RawObject.empty)
      case Found(rawObject:RawObject@unchecked) =>
        reduce(rawObject)
      case Found(_) if dropBadTypes =>
        reduce(RawObject.empty)
      case Found(r) =>
        Failed(IncorrectTypeFailure(this, r))
      case f:Failed =>
        f
    }
  }

  /**
   * Unapply is only ever a simple prism to the value and its decoding
   * @param obj
   * @return
   */
  final def unapply(obj:D):Option[DObject] =
    PathLensOps.traverse(obj.value, _path).flatMap(_codec.unapply)
}

private[dsentric] trait MaybeExpectedObjectPropertyLens[D <: DObject] extends ExpectedObjectPropertyLensLike[D] with ApplicativeLens[D, Option[DObject]]{

  private[contracts] def __get(data:D, dropBadTypes:Boolean):MaybeAvailable[DObject] = {
    def reduce(rawObject:Map[String, Any]):Valid[DObject] =
      ObjectPropertyLensOps.reduce(this, rawObject, isIgnore2BadTypes(dropBadTypes)) match {
        case Right(rawObject) =>
          //its possible this will return an empty object.
          Found(new DObjectInst(rawObject))
        case Left(NonEmptyList(head, tail)) =>
          Failed(head, tail)
      }

    TraversalOps.maybeTraverse(data.value, this, dropBadTypes) match {
      case NotFound  =>
        reduce(RawObject.empty)
      case Found(rawObject:RawObject@unchecked) =>
        reduce(rawObject)
      case Found(_) if dropBadTypes =>
        reduce(RawObject.empty)
      case Found(r) =>
        Failed(IncorrectTypeFailure(this, r))
      case f:Failed =>
        f
      case PathEmptyMaybe =>
        PathEmptyMaybe
    }
  }

  /**
   * Unapply is only ever a simple prism to the value and its decoding
   * @param obj
   * @return
   */
  final def unapply(obj:D):Option[Option[DObject]] =
    TraversalOps.maybeTraverse(obj.value, this, false) match {
      case PathEmptyMaybe =>
        Some(None)
      case Found(r) =>
        _codec.unapply(r).map(Some(_))
      case _ =>
        None
    }
}

/**
 * Object lens for a Property which contains an object or could be empty.
 * @tparam D
 */
private[dsentric] trait MaybeObjectPropertyLens[D <: DObject] extends ObjectPropertyLens[D] {

  private[contracts] def __get(data:D, dropBadTypes:Boolean):MaybeAvailable[DObject] = {
    def reduce(rawObject:Map[String, Any]):MaybeAvailable[DObject] =
      ObjectPropertyLensOps.reduce(this, rawObject, isIgnore2BadTypes(dropBadTypes)) match {
        case Right(rawObject) if rawObject.isEmpty =>
          NotFound
        case Right(rawObject) =>
          Found(new DObjectInst(rawObject))
        case Left(NonEmptyList(head, tail)) =>
          Failed(head, tail)
      }

    TraversalOps.maybeTraverse(data.value, this, dropBadTypes) match {
      case NotFound =>
        NotFound
      case Found(rawObject:RawObject@unchecked) =>
        reduce(rawObject)
      case Found(_) if dropBadTypes =>
        NotFound
      case Found(r) =>
        Failed(IncorrectTypeFailure(this, r))
      case f:Failed =>
        f
      case PathEmptyMaybe =>
        PathEmptyMaybe
    }
  }


  final def unapply(obj:D):Option[Option[DObject]] =
    TraversalOps.maybeTraverse(obj.value, this, false) match {
      case PathEmptyMaybe => Some(None)
      case NotFound => Some(None)
      case Found(t:RawObject) =>
        Some(Some(new DObjectInst(t)))
      case Found(_) =>
        None
      case Failed(_, _) => None
    }

}

private[dsentric] object ObjectPropertyLensOps extends ReduceOps {


  /**
   * Reduces empty property fields, removing DCodec values that return NotFound
   * Ultimately clearing out empty Objects as well
   * Will also remove any nulls
   * */
  def reduce[D <: DObject](baseContract:BaseContract[D], obj:RawObject, badTypes:BadTypes):ValidStructural[RawObject] = {

    val init = reduceAdditionalProperties(baseContract, obj, badTypes)
    val drop = badTypes.nest == DropBadTypes
    baseContract._fields.foldLeft(init){
      case (Right(d), (_, p)) =>
        p.__reduce(d, drop)
      case (l@Left(nel), (_, p)) =>
        p.__reduce(obj, drop) match {
          case Right(_) =>
            l
          case Left(nel2) =>
            Left(nel ::: nel2)
        }
    }
  }

  private def reduceAdditionalProperties[D <: DObject](
                                                      baseContract:BaseContract[D],
                                                      obj:RawObject,
                                                      badTypes:BadTypes
                                                    ):ValidStructural[RawObject] = {
    val exclude = baseContract._fields.keySet
    baseContract match {
      case a:AdditionalProperties[Any, Any]@unchecked =>
        val excludedObject = obj -- exclude
        reduceMap(a._root, a._path, badTypes, DCodecs.keyValueMapCodec(a._additionalKeyCodec, a._additionalValueCodec), excludedObject) match {
          case Found(rawObject) if rawObject == excludedObject =>
            Right(obj)
          case Found(rawObject) =>
            Right(obj -- exclude ++ rawObject)
          case NotFound =>
            Right(excludedObject)
          case Failed(head, tail) =>
            Left(NonEmptyList(head, tail))
        }
      case _ =>
        obj.keys
          .filterNot(exclude)
          .map(k => ClosedContractFailure(baseContract._root, baseContract._path, k))
          .toList match {
          case head :: tail =>
            Left(NonEmptyList(head, tail))
          case Nil =>
            Right(obj)
        }

    }
  }
}


