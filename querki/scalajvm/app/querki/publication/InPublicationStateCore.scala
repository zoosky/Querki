package querki.publication

import cats._
import cats.implicits._

import akka.actor.Actor.Receive
import akka.persistence.RecoveryCompleted

import funcakka.PersistentActorCore

import querki.globals._
import querki.spaces.{SpaceMessagePersistenceBase, SpacePure}
import querki.spaces.SpaceMessagePersistence._
import querki.time.DateTime

/**
 * This represents the main logic of the InPublicationStateActor. This is a PersistentActor that just stores
 * the Space Events for Publishables, which don't move over to the main Space Actor until those Things
 * get Published. It maintains a secondary "SpaceState", which is just the sum of those Events, so that
 * Users who have Publication access can overlay it onto the public Space.
 */
trait InPublicationStateCore extends SpacePure with PersistentActorCore with SpaceMessagePersistenceBase with EcologyMember {
  
  lazy val Basic = interface[querki.basic.Basic]
  lazy val Core = interface[querki.core.Core]
  lazy val System = interface[querki.system.System]
  
  lazy val SystemState = System.State
  
  //////////////////////////////////////////////////
  //
  // Abstract members
  //
  // These are all implemented very differently in the asynchronous, Akka Persistence-based, real Actor
  // vs. the synchronous test implementation.
  //

  /**
   * Tell the rest of the troupe about this.
   */
  def notifyChanges(curState:CurrentPublicationState):Unit
  
  def respondWithState(curState:CurrentPublicationState):Unit
  
  def respondPublished():Unit
  
  /////////////////////////////////////////////////
  
  def toPersistenceId(id:OID) = "inpubstate-" + id.toThingId.toString
  def persistenceId = toPersistenceId(id)
  
  var _pState:Option[CurrentPublicationState] = None
  def pState = _pState.getOrElse(CurrentPublicationState(Map.empty))
  def setState(s:CurrentPublicationState) = {
    _pState = Some(s)
    notifyChanges(s)
  }
  def addEvent(curState:CurrentPublicationState, evt:SpaceEvent):CurrentPublicationState = {
    // TODO: I *really* should be using a lens library here...
    val (isDelete, oid) = evt match {
      case e:DHCreateThing => (false, e.id)
      case e:DHModifyThing => (false, e.id)
      case e:DHDeleteThing => (true, e.id)
      case _ => throw new Exception(s"InPublicationStateCore received unexpected event $evt")
    }
    if (isDelete) {
      curState.copy(changes = curState.changes - oid)
    } else {
      val existing = curState.changes.get(oid).getOrElse(Vector.empty)
      curState.copy(changes = curState.changes + (oid -> (existing :+ evt)))
    }
  }
  
  def receiveCommand:Receive = {
    case evt:SpaceEvent => {
      persistAnd(evt).map { _ =>
        setState(addEvent(pState, evt))
      }
    }
    
    case AddPublicationEvents(evts) => {
      persistAllAnd(evts).map { _ =>
        val s = (pState /: evts) { (curState, evt) =>
          addEvent(curState, evt)
        }
        setState(s)
        respondWithState(pState)
      }
    }
    
    // This Thing has been Published, which means we can delete it from our local state:
    case ThingPublished(who, oid) => {
      implicit val s = pState
      val evt = DHDeleteThing(who, oid, DateTime.now)
      persistAnd(evt).map { _ =>
        setState(addEvent(pState, evt))
        respondPublished()
      }
    }
  }
  
  def receiveRecover:Receive = {
    case evt:SpaceEvent => {
      setState(addEvent(pState, evt))
    }
    
    case RecoveryCompleted => {
      // Only send the message if there have been some events. In most Spaces, this won't do anything:
      _pState.foreach { state =>
        notifyChanges(pState)
      }
    }
  }
}