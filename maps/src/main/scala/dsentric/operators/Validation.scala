package dsentric.operators

import dsentric.contracts.{Property, _}
import dsentric._
import dsentric.failure.{ClosedContractFailure, IncorrectKeyTypeFailure, IncorrectTypeFailure, ValidationFailures}


trait ClosedFields

object Validation {
  def validateContract[D <: DObject](contract:BaseContract[D], value:RawObject, maybeCurrentState:Option[RawObject]):ValidationFailures = {

    @inline
    def validateObjectsProperty[D2 <: DObject, D3 <: DObject](field:String, property:ObjectsProperty[D2, D3], value:RawObject):ValidationFailures =
      value.getOrElse(field, RawArray.empty) match {
        case array:RawArray@unchecked =>
          val (failures, maybeCollection) = array.zipWithIndex.foldRight((ValidationFailures.empty, Option(List.empty[D3]))){
            case ((obj:RawObject@unchecked, index), (failures, maybeElements)) =>
              property._valueCodec.unapply(obj).fold[(ValidationFailures, Option[List[D3]])]{
                val f = IncorrectTypeFailure(property._root, property._path \ index, property._valueCodec, obj.getClass)
                (f :: failures) -> None
              } { t  =>
                val fs = validateContract(property._contract, obj, None).map(_.rebase(property._root, property._path \ index))
                (fs ++ failures) -> maybeElements.map(t :: _)
              }

            case ((raw, index), (failures, _)) =>
              val f = IncorrectTypeFailure(property._root, property._path \ index, property._valueCodec, raw.getClass)
              (f :: failures) -> None
          }
          //if we can transform the vector we can validate the property
          maybeCollection.foldLeft(failures)((f, c) => f ++ validatePropertyOperators(field, property, Some(array), Some(c.toVector), maybeCurrentState))
        case DNull =>
          validatePropertyOperators(field, property, Some(RawArray.empty), Some(Vector.empty[D3]), maybeCurrentState)
        case raw =>
          ValidationFailures(IncorrectTypeFailure(property._root, property._path, property._codec, raw.getClass))
      }

    @inline
    def validateMapObjectsProperty[K, D2 <: DObject, D3 <: DObject](field:String, property:MapObjectsProperty[D2, K, D3], value:RawObject, maybeCurrentState:Option[RawObject]):ValidationFailures =
      value.getOrElse(field, RawObject) match {
        case map: RawObject@unchecked =>
          val (failures, maybeMap) =
            map.foldRight((ValidationFailures.empty, Option(Map.empty[K, D3]))){
              case ((key, obj:RawObject@unchecked), (failures, maybeMap)) =>
                val (failures2, maybeKey) =
                  property._codec.keyCodec.unapply(key).fold[(ValidationFailures, Option[K])]{
                    (IncorrectKeyTypeFailure(property._root, property._path, property._codec.keyCodec, key.getClass) :: failures) -> None
                  }{k => failures -> Some(k)}

                property._valueCodec.unapply(obj).fold[(ValidationFailures, Option[Map[K, D3]])]{
                  val f = IncorrectTypeFailure(property._root, property._path \ key, property._valueCodec, obj.getClass)
                  (f :: failures2) -> None
                } { t  =>
                  val cs = maybeCurrentState.flatMap(_.get(field).collect {
                    case r: RawObject@unchecked =>
                      r.get(key).collect {case r2: RawObject@unchecked => r2 }
                  }.flatten)
                  val fs = validateContract(property._contract, obj, cs).map(_.rebase(property._root, property._path \ key))
                  val maybeMap2 =
                    for {
                      m <- maybeMap
                      k <- maybeKey
                    } yield m + (k -> t)
                  (fs ++ failures2) -> maybeMap2
                }

              case ((key, raw), (failures, _)) =>
                val failures2 =
                  if (property._codec.keyCodec.unapply(key).isEmpty)
                    IncorrectKeyTypeFailure(property._root, property._path, property._codec.keyCodec, key.getClass) :: failures
                  else failures
                val failures3 =
                  IncorrectTypeFailure(property._root, property._path \ key, property._codec.valueCodec, raw.getClass) :: failures2
                failures3 -> None
            }
          //if we can transform the vector we can validate the property
          maybeMap.foldLeft(failures)((f, c) => f ++ validatePropertyOperators(field, property, Some(map), Some(c), maybeCurrentState))

        case DNull =>
          validatePropertyOperators(field, property, Some(RawObject.empty), Some(Map.empty[K, D3]), maybeCurrentState)
        case raw =>
          ValidationFailures(IncorrectTypeFailure(property._root, property._path, property._codec, raw.getClass))
      }


    @inline
    def validateContractProperty[D2 <: DObject](field:String, property:BaseContract[DObject] with Property[D2, _], value:RawObject, maybeCurrentState:Option[RawObject]):ValidationFailures =
      value.get(field).collect {
        case rv:RawObject@unchecked =>
          validateContract(property, rv, maybeCurrentState.flatMap(_.get(field).collect{case rs:RawObject@unchecked => rs}))
      }.getOrElse(ValidationFailures.empty)

    @inline
    def validateProperty[D2 <: DObject, T](field:String, property:Property[D2, T], value:RawObject, maybeCurrentState:Option[RawObject], default: => Option[T]):ValidationFailures =
      value.get(field) match {
        case Some(DNull) =>
          validatePropertyOperators(field, property, Some(DNull), None, maybeCurrentState)
        case Some(v) =>
          property._codec.unapply(v)
            .fold(ValidationFailures(IncorrectTypeFailure(property, v.getClass))){ maybeT =>
              validatePropertyOperators(field, property, Some(v), Some(maybeT), maybeCurrentState)
            }
        case None =>
          default.fold(validatePropertyOperators(field, property, None, None, maybeCurrentState)){t =>
            validatePropertyOperators(field, property, Some(property._codec(t)), Some(t), maybeCurrentState)
          }

      }

    @inline
    def validatePropertyOperators[D2 <: DObject, T](field:String, property:Property[D2, T], maybeValue:Option[Raw], maybeT:Option[T], maybeCurrentState:Option[RawObject]):ValidationFailures =
      property._dataOperators.collect{
        case validator:ValueValidator[T]@unchecked =>
          validator(property._root, property._path, maybeT, maybeCurrentState.flatMap(_.get(field)).flatMap(property._codec.unapply))
        case validator:RawValidator[T]@unchecked =>
          validator(property._root, property._path, maybeValue, maybeCurrentState.flatMap(_.get(field)))
      }.flatten

    @inline
    def validateClosed[D2 <: DObject](baseContract:BaseContract[D], value:RawObject): ValidationFailures =
      if (baseContract.isInstanceOf[ClosedFields]) {
        value.keySet.filterNot(baseContract._fields.keySet).map { k =>
          baseContract match {
            case p: Property[D2, _]@unchecked =>
              ClosedContractFailure(p._root, p._path, k)
            case b: ContractFor[D] =>
              ClosedContractFailure(b, Path.empty, k)
          }
        }.toList
      }
      else
        ValidationFailures.empty

    validateClosed(contract, value) ++
    contract._fields.foldLeft(ValidationFailures.empty){
      case (failures, (field, property:BaseContract[DObject]@unchecked with Property[D, _])) =>
        failures ++
        validateProperty(field, property, value, maybeCurrentState, None) ++
        validateContractProperty(field, property, value, maybeCurrentState)
      case (failures, (field, property:ObjectsProperty[D, _]@unchecked)) =>
        failures ++
        //validateProperty(field, property, value, maybeCurrentState, Some(Vector.empty)) ++
        validateObjectsProperty(field, property, value)
      case (failures, (field, property:MapObjectsProperty[D, _, _]@unchecked)) =>
        failures ++
        //validateProperty(field, property, value, maybeCurrentState, Some(property._codec.unapply(RawObject.empty).get)) ++
        validateMapObjectsProperty(field, property, value, maybeCurrentState)
      case (failures, (field, property)) =>
        failures ++
        validateProperty(field, property, value, maybeCurrentState, None)
    }
  }

}