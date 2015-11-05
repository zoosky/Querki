package querki.ql

import scala.reflect.ClassTag

import querki.values.SpaceState

import models.{AsOID, Collection, Property, PropertyBundle, PType, Thing, ThingId}

import querki.ecology._
import querki.globals._
import querki.types.ModelTypeBase
import querki.util.PublicException
import querki.values.{QLContext, QValue, SpaceState}

/**
 * Extra information that can get picked up and carried along with the InvocationValue. Pulled out to here,
 * to keep it from clogging up the logic of the main InvocationValueImpl.
 */
private [ql] case class IVMetadata(
    // Allows the Function to declare which PType it expects to return. This allows us to preserve type safety
    // even when we return QNone.
    returnType:Option[PType[_]] = None,
    preferredColl:Option[Collection] = None)
{
  /**
   * This method is the heart of IVMetadata, allowing us to combine metadatas in flatMap(). The copy
   * should account for all parameters.
   */
  def +(other:IVMetadata) = {
    this.copy(
        returnType = this.returnType orElse other.returnType,
        preferredColl = this.preferredColl orElse other.preferredColl)
  }
}

private[ql] case class IVData[T](
    vs:Iterable[T],
    metadata:IVMetadata = IVMetadata()) 

private[ql] case class InvocationValueImpl[T](inv:Invocation, fut:Future[IVData[T]])(implicit val ecology:Ecology) 
  extends InvocationValue[T] with EcologyMember
{ self =>
  lazy val QL = interface[QL]
  
  def map[R](f:T => R):InvocationValue[R] = {
    val maps = fut.map(data => IVData(data.vs.map(v => f(v)), data.metadata))
    InvocationValueImpl(inv, maps)
  }
  
  def flatMap[R](f:T => InvocationValue[R]):InvocationValue[R] = {
    val results = fut.flatMap { data =>
      val subs = data.vs.map(f(_)).map(_.asInstanceOf[InvocationValueImpl[R]])
      val subFuts = subs.map(_.fut)
      Future.sequence(subFuts) map { subDatas =>
        val resultVs = subDatas.map(_.vs).flatten
        val resultMetas = (data.metadata /: subDatas.map(_.metadata)) (_ + _)
        IVData(resultVs, resultMetas)
      }
    }
    
    InvocationValueImpl(inv, results)
  }

  /**
   * Our implementation of withFilter, for "if" statements in for comprehensions.
   */
  class WithFilterImpl(f:T => Boolean) extends WithFilter[T] {
    def map[R](mf:T => R):InvocationValue[R] = 
      new InvocationValueImpl(inv, self.fut.map(data => IVData(data.vs.filter(f), data.metadata))).map(mf)
    def flatMap[R](mf:T => InvocationValue[R]):InvocationValue[R] = 
      new InvocationValueImpl(inv, self.fut.map(data => IVData(data.vs.filter(f), data.metadata))).flatMap(mf)
    def withFilter(g:T => Boolean):WithFilter[T] = new WithFilterImpl((x => f(x) && g(x)))
  }
  def withFilter(f:T => Boolean):WithFilter[T] = new WithFilterImpl(f)
    
  def get:Future[Iterable[T]] = fut.map(_.vs)
}

object InvocationValueImpl {
  def apply[T](ex:PublicException)(implicit inv:Invocation, ecology:Ecology):InvocationValueImpl[T] = 
    InvocationValueImpl[T](inv, Future.failed(ex))
  def apply[T](vs:Iterable[T])(implicit inv:Invocation, ecology:Ecology):InvocationValueImpl[T] = 
    InvocationValueImpl[T](inv, Future.successful(IVData(vs)))
  def apply(meta:IVMetadata)(implicit inv:Invocation, ecology:Ecology):InvocationValueImpl[Boolean] =
    InvocationValueImpl(inv, Future.successful(IVData(Some(true), meta)))
}

private[ql] case class InvocationImpl(invokedOn:Thing, receivedContext:QLContext, val definingContext:Option[QLContext], paramsOpt:Option[Seq[QLPhrase]])(implicit val ecology:Ecology) 
  extends Invocation with EcologyMember
{
  lazy val QL = interface[querki.ql.QL]
  lazy val Core = interface[querki.core.Core]
  lazy val Types = interface[querki.types.Types]
  
  lazy val displayName = invokedOn.displayName
  lazy val LinkType = Core.LinkType
  
  implicit val inv = this
  
  def error[VT](name:String, params:String*) = InvocationValueImpl[VT](PublicException(name, params:_*))
  
  def test(predicate: => Boolean, errorName:String, params: => Seq[Any]):InvocationValue[Boolean] = {
    if (predicate)
      InvocationValueImpl(Some(true))
    else
      InvocationValueImpl(PublicException(errorName, params:_*))
  }
  
  def returnsType(pt:PType[_]):InvocationValue[Boolean] = {
    InvocationValueImpl(IVMetadata(returnType = Some(pt)))
  }
  
  def preferCollection(coll:Collection):InvocationValue[Boolean] = {
    InvocationValueImpl(IVMetadata(preferredColl = Some(coll)))
  }
  
  def preferDefiningContext:Invocation = {
    definingContext match {
      case Some(c) => this.copy(receivedContext = c)
      case None => this.copy(definingContext = Some(receivedContext))
    }
  }
  
  def contextTypeAs[T : ClassTag]:InvocationValue[T] = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    if (clazz.isInstance(context.value.pType))
      InvocationValueImpl(Some(context.value.pType.asInstanceOf[T]))
    else
      error("Func.wrongType", displayName, context.value.pType.displayName)
  }
  
  def contextElements:InvocationValue[QLContext] = {
    if (context.useCollection) {
      InvocationValueImpl(Some(context))
    } else {
      val contexts = context.value.cv.map(elem => context.next(Core.ExactlyOne(elem)))
      InvocationValueImpl(contexts)
    }
  }
  
  def contextValue:InvocationValue[QValue] = {
    InvocationValueImpl(Some(context.value))
  }
  
  def wrap[T](v:T):InvocationValue[T] = {
    InvocationValueImpl(Some(v))
  }
  
  def fut[T](fut:Future[T]):InvocationValue[T] = {
    InvocationValueImpl(this, fut.map(t => IVData(Some(t))))
  }
  
  def opt[T](opt:Option[T], errOpt:Option[PublicException] = None):InvocationValue[T] = {
    opt match {
      case Some(v) => InvocationValueImpl(Some(v))
      case None => {
        errOpt match {
          case Some(err) => InvocationValueImpl(err)
          // No error specified if this isn't true, so we're simply passing Empty along:
          case None => InvocationValueImpl(None)
        }
      }
    }
  }

  def iter[T](it:Iterable[T], errOpt:Option[PublicException] = None):InvocationValue[T] = {
    errOpt match {
      case Some(ex) => InvocationValueImpl(ex)
      case None => InvocationValueImpl(it)
    }
  }
  
  def contextFirstAs[VT](pt:PType[VT]):InvocationValue[VT] = {
    context.value.firstAs(pt) match {
      case Some(v) => InvocationValueImpl(Some(v))
      case None => error("Func.notThing", displayName)
    }
  }
  
  def contextAllAs[VT](pt:PType[VT]):InvocationValue[VT] = {
    if (!context.value.matchesType(pt))
      error("Func.notThing", displayName)
    else {
      val vs = context.value.flatMap(pt)(Some(_))
      InvocationValueImpl(vs)
    }
  }
  
  def contextFirstThing:InvocationValue[Thing] = {
    contextFirstAs(Core.LinkType).flatMap { oid =>
      state.anything(oid) match {
        case Some(thing) => InvocationValueImpl(Some(thing))
        case None => error("Func.unknownThing", displayName)
      }
    }
  }
  
  def contextAllThings:InvocationValue[Thing] = {
    if (!context.value.matchesType(Core.LinkType))
      error("Func.notThing", displayName)
    else {
      val ids = context.value.flatMap(Core.LinkType)(Some(_))
      val thingsOpt = ids.map(state.anything(_))
      if (thingsOpt.forall(_.isDefined))
        InvocationValueImpl(thingsOpt.flatten)
      else
        error("Func.unknownThing", displayName)
    }    
  }
  
  def contextAllBundles:InvocationValue[PropertyBundle] = {
    if (context.value.matchesType(Core.LinkType)) {
      val ids = context.value.flatMap(Core.LinkType)(Some(_))
      val thingsOpt = ids.map(state.anything(_))
      if (thingsOpt.forall(_.isDefined))
        InvocationValueImpl(thingsOpt.flatten)
      else
        error("Func.unknownThing", displayName)      
    } else context.value.pType match {
      case mt:ModelTypeBase => {
        val bundles = context.value.flatMap(mt)(Some(_))
        InvocationValueImpl(bundles)
      }
      case _ => error("Func.notThing", displayName)
    } 
  }
  
  def contextBundlesAndContexts:InvocationValue[(PropertyBundle, QLContext)] = {
    if (context.value.matchesType(Core.LinkType)) {
      val ids = context.value.flatMap(Core.LinkType)(Some(_))
      val thingsOpt = ids.map(state.anything(_))
      if (thingsOpt.forall(_.isDefined))
        InvocationValueImpl(thingsOpt.flatten.map(t => (t, context.next(Core.ExactlyOne(Core.LinkType(t))))))
      else
        error("Func.unknownThing", displayName)      
    } else context.value.pType match {
      case mt:ModelTypeBase => {
        val pairs = context.value.cv.map(elem => (elem.get(mt), context.next(Core.ExactlyOne(elem))))
        InvocationValueImpl(pairs)
      }
      case _ => error("Func.notThing", displayName)
    } 
  }
  
  def bundlesAndContextsForProp(prop:Property[_,_]):InvocationValue[(PropertyBundle, QLContext)] = {
    
    def wrapContexts(bundle:PropertyBundle):Iterable[(PropertyBundle, QLContext)] = {
      context.value.cv.map(elem => (bundle, context.next(Core.ExactlyOne(elem))))
    }
    
    def withLexicalContext:Option[PropertyBundle] = {
      for {
        parser <- context.parser
        lex <- parser.lexicalThing
      }
        yield lex
    }

    // Note that the elemContexts returned here are the same as the bundles. It isn't strictly clear that
    // that is correct -- conceptually, when we've walked back up the stack, we should be using the received
    // context as the elemContexts, I think. But things get weirdly multiplicative when we do that, and I'm
    // not sure how to correctly tame that.
    def withCurrentContext(current:QLContext):Option[InvocationValue[(PropertyBundle, QLContext)]] = {
      if (current.value.matchesType(Core.LinkType)) {
        val ids = current.value.flatMap(Core.LinkType)(Some(_))
        val thingsOpt = ids.map(state.anything(_))
        val things = thingsOpt.flatten
        val pairs = things.map(t => (t, context.next(Core.ExactlyOne(Core.LinkType(t)))))
        if (things.exists(_.hasProp(prop)))
          Some(InvocationValueImpl(pairs))
        else
          None
      } else current.value.pType match {
        case mt:ModelTypeBase => {
          val pairs = current.value.cv.map(elem => (elem.get(mt), context.next(Core.ExactlyOne(elem))))
          if (pairs.exists(_._1.hasProp(prop)))
            Some(InvocationValueImpl(pairs))
          else
            None
        }
        case _ => None
      }
    }

    // Walk recursively back the Context chain, until we find one that was a Thing (that is, a LinkType), or
    // we run out of Contexts. Note that we start with the one that was *already* tried, because we need to
    // inject lexical checking into this pathway:
    def walkNonThingContexts(previous:QLContext):InvocationValue[(PropertyBundle, QLContext)] = {
      if (previous.parentOpt.isEmpty || previous.value.matchesType(LinkType)) {
        // Either we hit the end of the chain, or we hit a Link -- either way, time to stop:
        InvocationValueImpl(this, Future.successful(IVData(None, IVMetadata(returnType = Some(LinkType)))))
      } else {
        // Keep walking back up the chain, to see if we find something:
        val current = previous.parent
        withCurrentContext(current) match {
          case Some(result) => result
          case None => walkNonThingContexts(current)
        }
      }
    }
    
    if (definingContext.isDefined) {
      // If there is a defining context, that is where we should be working from:
      val result = for {
        dc <- definingContext
        id <- dc.value.firstAs(Core.LinkType)
        thing <- state.anything(id)
      }
        yield InvocationValueImpl(wrapContexts(thing))
        
      result.getOrElse(error("Func.notThing", displayName))
    } else {
      // No defining context -- does the received context have the property?
      withCurrentContext(context) match {
        case Some(result) => result
        case None => {
          // Nope. Does the lexical context?
          withLexicalContext match {
            // IMPORTANT SUBTLETY: note that we're passing through the *bundle* of the lexical context, but the actual contexts are the received context!
            case Some(bundle) if (bundle.hasProp(prop)) => InvocationValueImpl(wrapContexts(bundle))
            case _ => {
              context.value.pType match {
                case mt:ModelTypeBase => {
                  // If this bundle is a Model Value, walk up the context chain.
                  walkNonThingContexts(context)
                }
                case _ => {
                  InvocationValueImpl(this, Future.successful(IVData(None, IVMetadata(returnType = Some(LinkType)))))                
                }
              }
            }
          }          
        }
      }
    }
  }
  
  def definingContextAsProperty:InvocationValue[Property[_,_]] = {
    definingContext match {
      case Some(defining) => {
        if (!defining.value.matchesType(Core.LinkType))
          error("Func.notThing", displayName)
        else {
          val ids:Iterable[ThingId] = defining.value.flatMap(Core.LinkType)(oid => Some(AsOID(oid)))
          val propOpts:Iterable[Option[Property[_,_]]] = ids.map(state.prop(_))
          if (propOpts.forall(_.isDefined)) {
            InvocationValueImpl(propOpts.flatten)
          } else
            error("Func.notProp", displayName)
        }          
      }
      case None => error("Func.missingDefiningContext", displayName)
    }    
  }
  
  def definingContextAsPropertyOf[VT](targetType:PType[VT]):InvocationValue[Property[VT,_]] = {
    definingContext match {
      case Some(defining) => {
        if (!defining.value.matchesType(Core.LinkType))
          error("Func.notThing", displayName)
        else {
          val ids:Iterable[ThingId] = defining.value.flatMap(Core.LinkType)(oid => Some(AsOID(oid)))
          val propOpts:Iterable[Option[Property[VT,_]]] = ids.map(state.prop(_).flatMap(prop => prop.confirmType(targetType)))
          if (propOpts.forall(_.isDefined)) {
            InvocationValueImpl(propOpts.flatten)
          } else
            error("Func.notProp", displayName)
    }          
      }
      case None => error("Func.missingDefiningContext", displayName)
    }  
  }
  
  def definingContextAsOptionalPropertyOf[VT](targetType:PType[VT]):InvocationValue[Option[Property[VT,_]]] = {
    definingContext match {
      case Some(defining) => {
        if (!defining.value.matchesType(Core.LinkType))
          error("Func.notThing", displayName)
        else {
          val ids:Iterable[ThingId] = defining.value.flatMap(Core.LinkType)(oid => Some(AsOID(oid)))
          val propOpts:Iterable[Option[Property[VT,_]]] = ids.map(state.prop(_).flatMap(prop => prop.confirmType(targetType)))
          if (propOpts.forall(_.isDefined)) {
            InvocationValueImpl(propOpts)
          } else
            error("Func.notProp", displayName)
        }          
      }
      case None => InvocationValueImpl(Some(None))
    }  
  }
  
  // TODO: merge these two functions. They're mostly alike...
  def processParam(paramNum:Int, processContext:QLContext = context):InvocationValue[QValue] = {
    paramsOpt match {
      case Some(params) if (params.length >= (paramNum + 1)) => {
        val resultFut = context.parser.get.processPhrase(params(paramNum).ops, processContext).flatMap { raw =>
          val processed = raw.value
          processed.firstAs(QL.ErrorTextType) match {
            // If there was an error, keep the error, and stop processing:
            case Some(errorText) => Future.failed(new PublicException("General.public", errorText))
            case None => Future.successful(Some(processed))
          }
        }
        InvocationValueImpl(inv, resultFut.map(IVData(_)))
      }
      case _ => error("Func.missingParam", displayName)
    }    
  }
  
  private def processParamFirstGuts[VT](paramNum:Int, pt:PType[VT], processContext:QLContext = context)(onEmpty: => Future[Iterable[VT]]):InvocationValue[VT] = 
  {
    paramsOpt match {
      case Some(params) if (params.length >= (paramNum + 1)) => {
        val resultFut:Future[Iterable[VT]] = context.parser.get.processPhrase(params(paramNum).ops, processContext).flatMap { raw =>
          val processed = raw.value
          if (processed.isEmpty)
            onEmpty
          else processed.firstAs(QL.ErrorTextType) match {
            case Some(errorText) => Future.failed(new PublicException("General.public", errorText))
            case None => processed.firstAs(pt) match {
              case Some(v) => Future.successful(Some(v))
              case None => Future.failed(PublicException("Func.paramWrongType", displayName, paramNum.toString, pt.displayName, processed.pType.displayName))                
            }
          }
        }
        InvocationValueImpl(inv, resultFut.map(IVData(_)))
      }
      case _ => error("Func.missingParam", displayName)
    }
  }
  
  def processParamFirstAs[VT](paramNum:Int, pt:PType[VT], processContext:QLContext = context):InvocationValue[VT] = {
    processParamFirstGuts(paramNum, pt, processContext) { 
      Future.failed(PublicException("Func.emptyParamValue", displayName))
    }
  }
  
  def processParamFirstOr[VT](paramNum:Int, pt:PType[VT], default:VT, processContext:QLContext = context):InvocationValue[VT] = {
    paramsOpt match {
      case Some(params) if (params.length >= (paramNum + 1)) => 
        processParamFirstGuts(paramNum, pt, processContext) {
          // Iff the result of the param was empty, return the default
          Future(Some(default))
        }
      case _ => wrap(default)
    }
  }
  
  def firstParamOrContextValue:InvocationValue[QValue] = {
    processParamNofM(0, 1)
  }
  
  def processParamNofM(paramNum:Int, expectedParams:Int, processContext:QLContext = context):InvocationValue[QValue] = {
    if (numParams < (expectedParams - 1))
      InvocationValueImpl(new PublicException("Func.insufficientParams", displayName, (expectedParams - 1)))
    else {
      if (numParams >= expectedParams)
        processParam(paramNum, processContext)
      else if (paramNum == 0)
        contextValue
      else
        processParam(paramNum - 1, processContext)
    }
  }
  
  def rawParam(paramNum:Int):InvocationValue[QLPhrase] = {
    paramsOpt match {
      case Some(params) if (params.length >= (paramNum - 1)) => InvocationValueImpl(Some(params(paramNum)))
      case _ => error("Func.missingParam", displayName)
    }
  }
  
  def WarningValue(msg:String) = QL.WarningValue(msg)
  
  /**
   * The "primary" context of this invocation. This is an exact synonym for receivedContext, and is the
   * one you usually care about. 
   */
  def context:QLContext = receivedContext
    
  implicit def state:SpaceState = context.state
  
  def parser = context.parser
  
  def lexicalThing = parser.flatMap(_.lexicalThing)
    
  def numParams:Int = paramsOpt match {
    case Some(params) => params.length
    case None => 0
  }
}
