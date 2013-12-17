package modules.person

// For talking to the SpaceManager:
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future

import models._
import models.Thing._
import models.system._
import models.system.OIDs._

import ql._

import querki.spaces.SpaceManager
import querki.spaces.messages.{ChangeProps, CreateThing, ThingError, ThingFound, ThingResponse}
import querki.util._
import querki.values._

import querki.identity._

import modules._
// TODO: Hmm. This is a Module-boundaries break. I think we need an interface layer:
import modules.email.EmailAddress

import querki.util._

import controllers.{PageEventManager, PlayRequestContext}

import play.api.Logger

  
// TODO: this Module should formally depend on the Email Module. Probably --
// it isn't entirely clear whether statically described Properties really
// require initialization-order dependencies. But I believe that the Person
// object shouldn't be constructed until after the Email Module has been.
class PersonModule(val moduleId:Short) extends modules.Module {

  object MOIDs {
    val PersonOID = oldMoid(1)
    val InviteLinkCmdOID = oldMoid(2)
    val IdentityLinkOID = oldMoid(3)
    val ChromelessInviteLinkOID = oldMoid(4)
    val MeMethodOID = oldMoid(5)
    val SecurityPrincipalOID = oldMoid(6)
    val ChromelessInvitesOID = moid(7)
    val InviteTextOID = moid(8)
    val SpaceInviteOID = moid(9)
  }
  import MOIDs._
  
  override def init = {
    PageEventManager.requestReceived += InviteLoginChecker
  }
  
  override def term = {
    PageEventManager.requestReceived -= InviteLoginChecker
  }

  /***********************************************
   * EXTERNAL REFS
   ***********************************************/

  lazy val Email = modules.Modules.Email
  lazy val emailAddressProp = Email.emailAddress
  lazy val emailAddressOID = Email.MOIDs.EmailPropOID
  
  lazy val AccessControl = modules.Modules.AccessControl
  
  lazy val urlBase = Config.getString("querki.app.urlRoot")
  
  /***********************************************
   * PROPERTIES
   ***********************************************/

  // The actual definition of this method is down below
  lazy val inviteLink = new SingleThingMethod(InviteLinkCmdOID, "Invite Link", "Generate a Link to invite someone to join this Space.", 
      """Place this command inside of an Email Message.
When the email is sent, it will be replaced by a link that the recipient of the email can use to log into this Space as a Person.""", doInviteLink(false))

  lazy val chromelessInviteLink = new SingleThingMethod(ChromelessInviteLinkOID, "Plain Invite Link", "Generate a Link to join this Space, with no Querki menus and such.", 
      """Place this command inside of an Email Message.
When the email is sent, it will be replaced by a link that the recipient of the email can use to log into this Space as a Person.
Unlike the ordinary Invite Link command, this one results in a page with no Querki menu bar, just your pages.
(NOTE: this will probably become a paid-users-only feature in the future. Also, this method isn't usually what you want any more;
instead, you usually want to set the Chromeless Invites property on your Space.)""", doInviteLink(true))

  lazy val identityLink = new SystemProperty(IdentityLinkOID, LinkType, Optional,
      toProps(
        setName("Person to Identity Link"),
        InternalProp(true),
        PropSummary("INTERNAL: points from a Space-scoped Person to a System-scoped Identity")))

  lazy val meMethod = new InternalMethod(MeMethodOID,
      toProps(
        setName("_me"),
        PropSummary("If the current user is a Person in the current Space, return that Person"),
        PropDetails("""_me is the usual way to customize a Space based on who is looking at it. If the page is being viewed by
            |a logged-in User, *and* they are a Member of this Space, it produces their Person record. If the viewer isn't
            |logged in, or isn't a Member, this will produce a Warning.
            |
            |TODO: this specifically does *not* currently work for the Owner of the Space. This is a bug, and we have to
            |figure out how to address it.
            |
            |NOTE: the high concept of _me is important, and will be continuing, but the details are likely to evolve a great
            |deal, to make it more usable. So don't get too invested in the current behaviour.""".stripMargin)))
  {
    override def qlApply(context:QLContext, params:Option[Seq[QLPhrase]] = None):QValue = {
      import PersonModule.UserPersonEnhancements
      val userOpt = context.request.requester
      implicit val state = context.state
      val personOpt = userOpt.flatMap(_.localPerson)
      personOpt.map(person => LinkValue(person)).getOrElse(WarningValue("You are not a member of this Space"))
    }
  }
  
  lazy val chromelessInvites = new SystemProperty(ChromelessInvitesOID, YesNoType, ExactlyOne,
      toProps(
        setName("Chromeless Invites"),
        PropSummary("Should invitees to this Space see it unadorned with Querki chrome?"),
        PropDetails("""If you set this to Yes on a Space or Thing, then Invite Links pointing
            |to that will show up without Querki chrome. That is, when they join, they'll just see your
            |pages, with no top menu or Querki footer.
            |
            |This feature is mainly intended for "white-labeling" Spaces, so that they don't look
            |as Querki-ish. We make it available for Spaces that care a great deal about how they look.
            |(It was originally designed for wedding invitations.)
            |
            |NOTE: this will probably become a paid-users-only feature in the future.""".stripMargin)))
  
  lazy val inviteText = new SystemProperty(InviteTextOID, LargeTextType, ExactlyOne,
      toProps(
        setName("Space Invitation Text"),
        AppliesToKindProp(Kind.Space),
        PropSummary("The text to use when inviting people to join your Space"),
        PropDetails("""This is the content of the invitation email, to go along with the standard Querki
            |invitation text.
            |
            |This is included in the Sharing and Security page, so you don't usually need to do anything
            |directly with it.""".stripMargin)))

  override lazy val props = Seq(
    inviteLink,
    
    chromelessInviteLink,
    
    identityLink,
    
    meMethod,
    
    chromelessInvites,
    
    inviteText,
    
    spaceInvite
  )
  
  /***********************************************
   * THINGS
   ***********************************************/
  
  lazy val securityPrincipal = ThingState(SecurityPrincipalOID, systemOID, SimpleThingOID,
      toProps(
        setName("Security Principal"),
        DisplayTextProp("""For internal use -- this the concept of a Thing that can be given permissions.""")))
  
  lazy val person = ThingState(PersonOID, systemOID, SecurityPrincipalOID,
      toProps(
        setName("Person"),
        InternalProp(true),
        IsModelProp(true),
        // TODO: this is a fugly declaration, and possibly unsafe -- do we have any
        // assurance that modules.Modules.Email has been constructed before this?
        (modules.Modules.Email.MOIDs.EmailPropOID -> Optional.QNone),
        DisplayTextProp("""This represents a Member of this Space.""")))
    
  override lazy val things = Seq(
    securityPrincipal,
    // The Person Model
    person
  )
   
  /***********************************************
   * METHOD CONTENTS
   ***********************************************/
  
  /**
   * Find the Identity that goes with this Person's email address, or create one if there
   * isn't already one.
   */
  private def setIdentityId(t:Thing, context:QLContext):OID = {
    implicit val s = context.state
    val emailAddr = t.first(emailAddressProp)
    val name = t.first(NameProp)
    // Get the Identity in the database...
    val identity = Identity.getOrCreateByEmail(emailAddr, name)
    // ... then point the Person to it, so we can use it later...
    val req = context.request
    // TODO: we shouldn't do this again if the Person is already pointing to the Identity:
    val changeRequest = ChangeProps(req.requester.get, s.owner, s.id, t.toThingId, toProps(identityLink(identity.id))())
    // TODO: eventually, all of this should get beefed up in various ways:
    // -- ChangeProps should carry the version stamp of t, so that race conditions can be rejected.
    // -- Ideally, we shouldn't send the email until the Identity has been fully established. Indeed, this whole
    //    business really *ought* to be transactional.
    SpaceManager.ask(changeRequest) { resp:ThingResponse =>
      resp match {
        case ThingFound(id, state) => Logger.info("Added identity " + identity.id + " to Person " + t.toThingId)
        case ThingError(error, stateOpt) => Logger.error("Unable to add identity " + identity.id + " to Person " + t.toThingId + ": " + error.msgName)
      }
    }
    // ... and finally, return it so we can use it now:
    identity.id
  }
  
  def idHash(personId:OID, identityId:OID):EncryptedHash = {
    Hasher.calcHash(personId.toString + identityId.toString)
  }
  
  val identityParam = "identity"
  val identityName = "identityName"
  val identityEmail = "identityEmail"
  val personParam = "person"
  val inviteParam = "invite"
    
  def doInviteLink(chromelessIn:Boolean)(t:Thing, context:QLContext):QValue = {
    implicit val state = context.state
    val personOpt =
      for {
        rootId <- context.root.value.firstAs(LinkType);
        rootThing <- state.anything(rootId);
        if (rootThing.isAncestor(PersonOID))
      }
        yield rootThing
        
    personOpt match {
      case Some(person) => {
        val chromeless = chromelessIn || t.ifSet(chromelessInvites) || state.ifSet(chromelessInvites)
        // Get the Identity linked from this Person. If there isn't already one, make one.
	    val identityProp = person.localProp(identityLink)
	    val identityId = identityProp match {
	      case Some(propAndVal) if (!propAndVal.isEmpty) => propAndVal.first
  	      // This will set identityProp, as well as getting the Identity's OID:
	      case _ => setIdentityId(person, context)
	    }
	    val hash = idHash(person.id, identityId)
	    
	    // TODO: this surely belongs in a utility somewhere -- it constructs the full path to a Thing, plus some paths.
	    // Technically speaking, we are converting a Link to an ExternalLink, then adding params.
	    val url = urlBase + "u/" + state.owner.toThingId + "/" + state.name + "/" + t.toThingId + 
	      "?" + personParam + "=" + person.id.toThingId + "&" +
	      identityParam + "=" + hash +
	      (if (chromeless) "&cl=on" else "")
	    ExactlyOne(ExternalLinkType(url))
      }
      case _ => WarningValue("Invite Link is only defined when sending email")
    }
  }
  
  /*************************************************************
   * INVITATION MANAGEMENT
   *************************************************************/

  // TODO: this belongs in a utility library somewhere:
  def encodeURL(url:String):String = java.net.URLEncoder.encode(url, "UTF-8")
  def decodeURL(url:String):String = java.net.URLDecoder.decode(url, "UTF-8")
  
  lazy val spaceInvite = new SingleContextMethod(SpaceInviteOID,
      toProps(
        setName("_spaceInvitation"), 
        InternalProp(true),
        PropSummary("Generate a Link to invite someone to join this Space."), 
        PropDetails("""This is intended for internal use only. It is used to generate
            |the link that is sent to invitees to your Space""".stripMargin)))
  {
    def fullyApply(mainContext:QLContext, partialContext:QLContext, params:Option[Seq[QLPhrase]]):QValue = {
      implicit val state = mainContext.state
      
      // TODO: we're using EmailModule to inject the Person as the root context. That's ghastly, and
      // no longer necessary: add a backdoor to add the Person as an annotation instead.
      val inviteOpt =
        for {
          rootId <- mainContext.root.value.firstAs(LinkType);
          person <- state.anything(rootId);
          if (person.isAncestor(PersonOID));
          emailPropOpt <- person.getPropOptTyped(emailAddressProp);
          email <- emailPropOpt.firstOpt;
          rc <- mainContext.requestOpt;
          // This is the value that we're going to use to identify this person when they
          // click on the link. We need to include the email address, to guard against it
          // being changed in the Person record after the email is sent. (Which could be
          // used as a spam vector, I suspect.)
          idString = person.id.toString + ":" + email.addr;
          signed = Hasher.sign(idString, Email.emailSepChar);
          encoded = encodeURL(signed.toString);
          // TODO: this surely belongs in a utility somewhere -- it constructs the full path to a Thing, plus some paths.
	      // Technically speaking, we are converting a Link to an ExternalLink, then adding params.
	      url = urlBase + "u/" + rc.ownerHandle + "/" + state.toThingId +
	        "/?" + inviteParam + "=" + encoded
        }
        yield HtmlValue(s"""<b><a href="$url">Click here</a></b> to accept the invitation.""")
        
      inviteOpt.getOrElse(WarningValue("This appears to be an incorrect use of _spaceInvitation."))
    }
  }
  
  case class InvitationResult(invited:Seq[EmailAddress], alreadyInvited:Seq[EmailAddress])
  
  /**
   * Invite some people to join this Space. rc.state must be established (and authentication dealt with) before
   * we get here.
   * 
   * TODO: restructure this to be Async!
   */
  def inviteMembers(rc:RequestContext, invitees:Seq[EmailAddress]):InvitationResult = {
    val originalState = rc.state.get
    
    // Filter out any of these email addresses that have already been invited:
    val currentMembers = originalState.descendants(PersonOID, false, true)
    // This is a Map[address,Person] of all the members of this Space:
    // TODO: make this smarter! Should work for *all* addresses of the members, not just the ones in the Person
    // records! Sadly, that probably means a DB lookup.
    // TODO: the use of toLowerCase in this method is an abstraction break, as is the fact that we're digging
    // into EmailAddress.addr. Instead, the Email PType should provide enough machinery to be able to do this
    // properly, by extending matches() with some proper comparators.
    val currentEmails = currentMembers.
      map(member => (member.getPropOptTyped(emailAddressProp)(originalState) -> member)).
      filter(pair => (pair._1.isDefined && !pair._1.get.isEmpty)).
      map(entry => (entry._1.get.first.addr.toLowerCase(), entry._2)).
      toMap
    val (existingEmails, newEmails) = invitees.partition(newEmail => currentEmails.contains(newEmail.addr.toLowerCase()))
    
    val existingPeople = existingEmails.flatMap(email => currentEmails.get(email.addr.toLowerCase()))
    
    // Create Person records for all the new ones:
    // TODO: add a CreateThings message that allows me to do multi-create:
    // TODO: this is an icky side-effectful way of getting the current state:
    var updatedState = originalState
    val people = newEmails.map { address =>
      // Create a Display Name. No, we're not going to worry about uniqueness -- Display Names are allowed
      // to be duplicated:
      val prefix = address.addr.takeWhile(_ != '@')
      val displayName = "Invitee " + prefix
      
      val propMap = 
        Thing.toProps(
          emailAddressProp(address.addr),
          DisplayNameProp(displayName),
          AccessControl.canReadProp(AccessControl.ownerTag))()
      val msg = CreateThing(rc.requester.get, rc.ownerId, updatedState.toThingId, Kind.Thing, PersonOID, propMap)
      implicit val timeout = Timeout(5 seconds)
      val future = SpaceManager.ref ? msg
      // TODO: EEEEVIL! This code is quick and dirty but wrong. This should all be Async, using SpaceManager.ask:
      Await.result(future, 5 seconds) match {
        case ThingFound(id, newState) => { updatedState = newState; newState.anything(id).get }
        case err => throw new Exception("Error trying to create an invitee: " + err) // TODO: improve this error path!
      }
    }
    
    implicit val finalState = updatedState
    val newRc = rc.withUpdatedState(updatedState)
    val context = updatedState.thisAsContext(newRc)
    val subjectQL = QLText(rc.ownerName + " has invited you to join the Space " + updatedState.displayName)
    val inviteLink = QLText("""
      |
      |------
      |
      |[[_spaceInvitation]]""".stripMargin)
    val bodyQL = updatedState.getPropOpt(inviteText).flatMap(_.firstOpt).getOrElse(QLText("")) + inviteLink
    // TODO: we probably should test that sentTo includes everyone we expected:
    val sentTo = Email.sendToPeople(context, people ++ existingPeople, subjectQL, bodyQL)
    
    InvitationResult(newEmails, existingEmails)
  }
  
  import controllers.PlayRequestContext
  /**
   * This is called via callbacks when we are beginning to render a page. It looks to see whether the
   * URL is an invitation to join this Space, and goes to the Invitation workflow if so.
   * 
   * TODO: this is dependent on PlayRequestContext, which means that it really belongs in controllers!
   */
  object InviteLoginChecker extends Contributor[PlayRequestContext,PlayRequestContext] {
    def notify(rc:PlayRequestContext, sender:Publisher[PlayRequestContext, PlayRequestContext]):PlayRequestContext = {
      val rcOpt =
        for (
          encodedInvite <- rc.firstQueryParam(inviteParam);
          spaceId <- rc.spaceIdOpt;
          ownerHandle <- rc.reqOwnerHandle;
          hash = SignedHash(encodedInvite, Email.emailSepChar);
          // TODO: we should do something smarter if this fails:
          if (Hasher.checkSignature(hash));
          SignedHash(_, _, msg, _) = hash;
          Array(personIdStr, emailAddrStr, _*) = msg.split(":");
          emailAddr = EmailAddress(emailAddrStr);
          updates = Map((personParam -> personIdStr), (identityEmail -> emailAddrStr))
        )
          yield rc.copy(sessionUpdates = rc.sessionUpdates ++ rc.returnToHereUpdate ++ updates,
              redirectTo = Some(controllers.routes.LoginController.handleInvite(ownerHandle, spaceId)))
              
      // This gets picked up in Application.withSpace(), and redirected as necessary.
      rcOpt.getOrElse(rc)
    }
  }
  
  /**
   * This checks all the preconditions, and sends a request off to the SpaceManager to attach the
   * local Person record to the Identity. If it all succeeds, this will eventually produce a ThingFound(personId, state).
   * 
   * TODO: this depends on Play, so it should be in controllers!
   */
  def acceptInvitation[B](rc:PlayRequestContext)(cb:ThingResponse => B):Option[Future[B]] = {
    for (
      personIdStr <- rc.sessionCookie(personParam);
      personId = OID(personIdStr);
      state <- rc.state;
      person <- state.anything(personId);
      user <- rc.requester;
      // TODO: currently, we're just taking the first identity, arbitrarily. But in the long run, I should be able
      // to choose which of my identities is joining this Space:
      identity <- user.identityBy(_ => true);
      membershipResult = User.addSpaceMembership(identity.id, state.id);
      changeRequest = ChangeProps(SystemUser, state.owner, state.id, person.toThingId, 
          toProps(
            identityLink(identity.id),
            DisplayNameProp(identity.name))())
    )
      yield SpaceManager.ask[ThingResponse, B](changeRequest)(cb)
  }
}

object PersonModule {
  import modules.Modules.Person._
  import modules.Modules.Person.MOIDs._
  
  def getPersonIdentity(person:Thing)(implicit state:SpaceState):Option[OID] = {
    for (
      identityVal <- person.getPropOpt(identityLink);
      identityId <- identityVal.firstOpt
        )
      yield identityId    
  }
  
  /**
   * Additional features of User, specifically for interacting with Person.
   */
  implicit class UserPersonEnhancements(user:User) {
    def hasPerson(personId:OID)(implicit state:SpaceState):Boolean = {
      state.anything(personId).map(hasPerson(_)).getOrElse(false)
    }
    def hasPerson(person:Thing)(implicit state:SpaceState):Boolean = {
      val idOpt = getPersonIdentity(person)
      idOpt.map(user.hasIdentity(_)).getOrElse(false)
    }
    
    def localPerson(implicit state:SpaceState):Option[Thing] = {
      state.
        descendants(PersonOID, false, true).
        filter(person => hasPerson(person)).
        headOption
    }
  }
  
  /**
   * Additional features of Identity, specifically for interacting with Person.
   */
  implicit class IdentityPersonEnhancements(identity:Identity) {
    def isPerson(person:Thing)(implicit state:SpaceState):Boolean = {
      val idOpt = getPersonIdentity(person)
      idOpt.map(_ == identity.id).getOrElse(false)
    }
    
    def localPerson(implicit state:SpaceState):Option[Thing] = {
      state.
        descendants(PersonOID, false, true).
        filter(person => isPerson(person)).
        headOption
    }    
  }
}