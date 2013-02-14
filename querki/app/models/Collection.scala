package models

import system.OIDs._

import Thing._

import ql._

trait PropValue {
  type cType = coll.implType
  type pType = PType[_]
  
  val coll:Collection
  def cv:cType
  
  def serialize(elemT:pType):String = coll.doSerialize(cv, elemT)
  def first = coll.first(this)
}

/**
 * A Collection is the Querki equivalent of a Functor in Category Theory. Properties
 * always combine a Type *and* a Collection. (You must explicitly state both Optional
 * or ExactlyOne.) 
 * 
 * As of this writing, it isn't obvious that Collections will be
 * required to be strictly monadic, but they probably all are. By being rigorous
 * and consistent about this, we make it much easier to write QL safely -- each
 * QL step is basically a flatMap.
 */
abstract class Collection(i:OID, s:OID, m:OID, pf:PropFetcher) extends Thing(i, s, m, Kind.Collection, pf) {
  
  type pType = PType[_]
  type implType <: Iterable[ElemValue]
  
  /**
   * Each Collection is required to implement this -- it is the deserializer for the
   * type.
   */
  protected def doDeserialize(ser:String, elemT:pType):implType
  final def deserialize(ser:String, elemT:pType):PropValue = makePropValue(doDeserialize(ser,elemT))
  
  /**
   * Also required for all Collections, to serialize values of this type.
   */
  def doSerialize(v:implType, elemT:pType):String 
//  final def serialize(v:PropValue, elemT:pType):String = doSerialize(v.cv, elemT)
  
  /**
   * Takes a value of this type, and turns it into displayable form. Querki
   * equivalent to toString.
   */
  def render(context:ContextBase)(v:PropValue, elemT:pType):Wikitext = {
    val renderedElems = v.cv.map(elem => elemT.render(context)(elem))
    Wikitext(renderedElems map (_.internal) mkString("\n"))    
  }
  
  /**
   * Also required for all Collections -- the default value to fall back on.
   */
  protected def doDefault(elemT:pType):implType
  final def default(elemT:pType):PropValue = makePropValue(doDefault(elemT))
  
  /**
   * Convenience wrapper for creating in-code PropValues.
   */
  def wrap(elem:ElemValue):implType
  def makePropValue(cv:implType):PropValue
  def apply(elem:ElemValue):PropValue = makePropValue(wrap(elem))
  
  import play.api.data.Form
  // TODO: this really doesn't belong here. We need to tease the HTTP/HTML specific
  // stuff out from the core concepts.
  // TODO: this will need refactoring, to get more complex on a per-Collection basis
  def fromUser(form:Form[_], prop:Property[_,_], elemT:pType):FormFieldInfo = {
    val fieldId = prop.id.toString
    val formV = form("v-" + fieldId).value
    val empty = form("empty-" + fieldId).value map (_.toBoolean) getOrElse false
    if (empty) {
      FormFieldInfo(prop, None, true, true)
    } else formV match {
      // Normal case: pass it to the PType for parsing the value out:
      case Some(v) => {
        if (elemT.validate(v))
          FormFieldInfo(prop, Some(apply(elemT.fromUser(v))), false, true)
        else
          FormFieldInfo(prop, None, true, false)
      }
      // There was no field value found. In this case, we take the default. That
      // seems strange, but this case is entirely valid in the case of a checkbox:
      case None => FormFieldInfo(prop, Some(default(elemT)), true, true)
    }
  }
  
  /**
   * Returns the head of the collection.
   * 
   * NOTE: this will throw an exception if you call it on an empty collection! It is the
   * equivalent of Option.get
   */
  final def first(v:PropValue):ElemValue = v.cv.head
  
  final def isEmpty(v:PropValue):Boolean = v.cv.isEmpty
  
  implicit def toIterable(v:implType):Iterable[ElemValue] = v.asInstanceOf[Iterable[ElemValue]]
}

/**
 * A null collection, whose sole purpose is to be the cType for the Name Property.
 * 
 * TBD: this is bloody dangerous, and we'll see how well it works. But we have nasty
 * chicken-and-egg problems otherwise -- every Thing has Properties, which have Collections,
 * which causes looping. In particular, we need a Collection for the initial PropValues
 * to point to.
 */
class NameCollection extends Collection(IllegalOID, systemOID, systemOID, () => emptyProps) {
  type implType = List[ElemValue]

  def doDeserialize(ser:String, elemT:pType):implType = List(elemT.deserialize(ser))

  def doSerialize(v:implType, elemT:pType):String = elemT.serialize(v.head)

  def doRender(context:ContextBase)(v:implType, elemT:pType):Wikitext = {
    elemT.render(context)(v.head)
  }
  def doDefault(elemT:pType):implType = {
    List(elemT.default)
  }
  def wrap(elem:ElemValue):implType = List(elem)
  def makePropValue(cv:implType):PropValue = NamePropValue(cv, NameCollection.this)
    
  private case class NamePropValue(cv:implType, coll:NameCollection) extends PropValue {}  
}
object NameCollection extends NameCollection {
  def bootProp(oid:OID, v:Any) = (oid -> makePropValue(wrap(ElemValue(v))))
}
