package querki.apps

import models._

import querki.data.TID
import querki.globals._
import querki.identity.User
import querki.time.DateTime
import querki.types.ModelTypeBase
import querki.values.SpaceState

private [apps] case class Extractees(state:SpaceState, typeModels:Set[OID], extractState:Boolean)

/**
 * The part of ExtractAppActor that computes what we're actually extracting from this Space.
 * 
 * This is pulled out solely to keep the file sizes comprehensible.
 */
private [apps] trait ExtracteeComputer { self:EcologyMember =>
  
  def Core:querki.core.Core
  def SystemSpace:SpaceState
  def systemId:OID
  
  def elements:Seq[TID]
  def name:String
  def owner:User
  def state:SpaceState
  
  /**
   * Creates the complete Extractees structure, with all the stuff we expect to pull out.
   * 
   * Note that this explicitly assumes there are no loops involved in the Model Types. That's a
   * fair assumption -- a lot of things will break if there are -- but do we need to sanity-check
   * for that?
   */
  def computeExtractees():Extractees = {
    val oids = elements
      .map(tid => ThingId(tid.underlying))
      .collect { case AsOID(oid) => oid }
      .toSet
    
    val (things, extractState) =
      if (oids.contains(state.id)) {
        (oids - state.id, true)
      } else {
        (oids, false)
      }
    
    val initState = 
      SpaceState(
        if (extractState) state.id else OID(1, 1),
        systemId,
        Map(Core.NameProp(name)),
        owner.mainIdentity.id,
        name,
        DateTime.now,
        Seq.empty,
        Some(SystemSpace),
        Map.empty,
        Map.empty,
        Map.empty,
        Map.empty,
        None            
      )
      
    val init = Extractees(initState, Set.empty, extractState)
    val withRoot = extractStateRoot(init)
    (withRoot /: oids) { (ext, elemId) => addThingToExtract(elemId, ext) }
  }
  
  def extractStateRoot(in:Extractees):Extractees = {
    if (in.extractState) {
      // If the Space depends on local Properties, make sure to include those, too:
      val withProps = (in /: state.props.keys) { (ext, propId) => addPropToExtract(propId, ext) }
      val s = withProps.state
      // Actually copy in the Space's Properties, *except* for the Name.
      // TODO: this will eventually need to also copy in the Model and the Apps, to be able to do
      // multi-level Apps. (And enhance the SpaceBuilder accordingly.) One step at a time, though.
      withProps.copy(state = s.copy(pf = (state.pf - Core.NameProp.id)))
    } else
      in
  }
  
  def addThingToExtract(id:OID, in:Extractees):Extractees = {
    if (in.state.things.contains(id))
      // Already done
      in
    else {
      state.thing(AsOID(id)) match {
        case Some(t) => {
          if (t.spaceId == state.id) {
            // Add all the props to the list...
            val withProps = (in /: t.props.keys) { (ext, propId) => addPropToExtract(propId, ext) }
            // ... and this thing:
            withProps.copy(state = withProps.state.copy(things = withProps.state.things + (id -> t)))
          } else
            // Not local, so don't extract it
            in
        }
        // Hmm. This is conceptually an error, but it *can* happen, so we just have to give up here:
        case None => in
      }
    }
  }
  
  def addPropToExtract(id:OID, in:Extractees):Extractees = {
    if (in.state.spaceProps.contains(id))
      in
    else {
      state.prop(AsOID(id)) match {
        case Some(p) => {
          if (p.spaceId == state.id) {
            // Add meta-props, if any...
            val withProps = (in /: p.props.keys) { (ext, propId) => addPropToExtract(propId, ext) }
            // ... the Type...
//            QLog.spew(s"Extracting prop with pType ${p.pType}:")
//            QLog.spewThing(p)(state)
            val withType = addTypeToExtract(p.pType, withProps)
            // ... and this Prop itself:
            withType.copy(state = withType.state.copy(spaceProps = withType.state.spaceProps + (id -> p)))
          } else
            in
        }
        case None => in
      }
    }
  }
  
  def addTypeToExtract(pt:PType[_], in:Extractees):Extractees = {
    if (in.state.types.contains(pt.id))
      in
    else {
      if (pt.spaceId == state.id) {
        // If this is a model type, we need to dive in and add that:
        pt match {
          case mt:ModelTypeBase => {
            // We specifically note that this is a Model Type, because those do *not* get shadow copies
            // in the new Space:
            val withMT = in.copy(typeModels = in.typeModels + mt.basedOn)
            val withType = withMT.copy(state = withMT.state.copy(types = withMT.state.types + (pt.id -> pt)))
//            QLog.spew(s"Extracting type:")
//            QLog.spewThing(pt)(state)
            addThingToExtract(mt.basedOn, withType)
          }
          case _ => in
        }
      } else
        in
    }
  }
}